package vip.mate.memory.governance;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.memory.MemoryProperties;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryWriteGovernanceServiceTest {

    private MemoryWriteLedgerMapper ledgerMapper;
    private MemoryProperties properties;
    private MemoryWriteGovernanceService service;
    private AtomicReference<MemoryWriteLedgerEntity> inserted;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                MemoryWriteLedgerEntity.class);
    }

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(MemoryWriteLedgerMapper.class);
        properties = new MemoryProperties();
        properties.setGovernanceEnabled(true);
        properties.setWriteMaxTokens(120);
        properties.setLongTermTokenBudget(240);
        properties.setRequireSourceRef(true);
        properties.setRejectNewsBody(true);
        service = new MemoryWriteGovernanceService(ledgerMapper, properties);
        inserted = new AtomicReference<>();
        when(ledgerMapper.selectList(any())).thenReturn(List.of());
        when(ledgerMapper.insert(any(MemoryWriteLedgerEntity.class))).thenAnswer(invocation -> {
            MemoryWriteLedgerEntity row = invocation.getArgument(0);
            row.setId(9001L);
            inserted.set(row);
            return 1;
        });
    }

    @Test
    @DisplayName("没有来源指针的长期记忆被拒绝并留痕")
    void requiresSourceReference() {
        MemoryWriteDecision decision = service.admit(request("user", "preferred_format", "偏好使用表格", "user-requested", null));

        assertFalse(decision.admitted());
        assertEquals(MemoryWriteGovernanceService.STATUS_REJECTED, decision.status());
        assertTrue(decision.reason().contains("来源指针"));
        assertEquals(MemoryWriteGovernanceService.STATUS_REJECTED, inserted.get().getStatus());
    }

    @Test
    @DisplayName("AI 动态正文和证据原文不会进入长期记忆")
    void blocksNewsEvidenceBody() {
        String article = "https://example.com/news\n" + "这是新闻正文。".repeat(80);

        MemoryWriteDecision decision = service.admit(request("reference", "ai_news_evidence", article,
                "ai-news-radar", "ai-news:event-1"));

        assertFalse(decision.admitted());
        assertEquals(MemoryWriteGovernanceService.STATUS_REJECTED, decision.status());
        assertTrue(decision.reason().contains("AI 动态事件/Wiki"));
    }

    @Test
    @DisplayName("自动提取与已有同 key 不一致时记录冲突，不静默覆盖")
    void autonomousConflictIsNotSilentlyApplied() {
        MemoryWriteLedgerEntity current = active("project", "editorial_tone", "务实简洁", 30, 1);
        when(ledgerMapper.selectList(any())).thenReturn(List.of(current));

        MemoryWriteDecision decision = service.admit(request("project", "editorial_tone", "轻松活泼", "nudge", "conversation:conv-2"));

        assertFalse(decision.admitted());
        assertEquals(MemoryWriteGovernanceService.STATUS_CONFLICTED, decision.status());
        assertEquals(current.getId(), inserted.get().getSupersedesId());
        assertEquals(2, inserted.get().getVersionNo());
    }

    @Test
    @DisplayName("显式用户请求可以形成版本替代，并且文件成功后才标记 APPLIED")
    void explicitUpdateSupersedesAfterFileWriteConfirmation() {
        MemoryWriteLedgerEntity current = active("user", "preferred_format", "表格", 12, 1);
        when(ledgerMapper.selectList(any())).thenReturn(List.of(current));

        MemoryWriteDecision decision = service.admit(request("user", "preferred_format", "先给结论再给表格",
                "user-requested", "conversation:conv-3"));
        assertTrue(decision.admitted());
        assertEquals(MemoryWriteGovernanceService.STATUS_PENDING, inserted.get().getStatus());
        assertEquals(current.getId(), inserted.get().getSupersedesId());
        when(ledgerMapper.selectById(9001L)).thenReturn(inserted.get());

        service.markApplied(9001L);

        assertEquals(MemoryWriteGovernanceService.STATUS_APPLIED, inserted.get().getStatus());
        verify(ledgerMapper, times(1)).update(any(), any());
        verify(ledgerMapper, times(1)).updateById(inserted.get());
    }

    @Test
    @DisplayName("token 预算满时不再把自动提取写入长期记忆")
    void respectsLongTermTokenBudget() {
        properties.setLongTermTokenBudget(30);
        MemoryWriteLedgerEntity existing = active("project", "current_plan", "x", 26, 1);
        when(ledgerMapper.selectList(any())).thenReturn(List.of(), List.of(existing));

        MemoryWriteDecision decision = service.admit(request("reference", "release_url",
                "https://example.com/releases/latest", "nudge", "conversation:conv-4"));

        assertFalse(decision.admitted());
        assertEquals(MemoryWriteGovernanceService.STATUS_REJECTED, decision.status());
        assertTrue(decision.reason().contains("token 预算已满"));
    }

    @Test
    @DisplayName("token 估算对中文采用保守字符计数")
    void estimatesCjkTokensConservatively() {
        assertEquals(4, MemoryWriteGovernanceService.estimateTokens("中文测试"));
        assertEquals(2, MemoryWriteGovernanceService.estimateTokens("abcdef"));
    }

    private MemoryWriteRequest request(String type, String key, String content, String source, String sourceRef) {
        return new MemoryWriteRequest(7L, 3L, "owner-7", type, key, content, source, "conv-1", sourceRef);
    }

    private MemoryWriteLedgerEntity active(String type, String key, String content, int tokens, int version) {
        MemoryWriteLedgerEntity row = new MemoryWriteLedgerEntity();
        row.setId(5001L);
        row.setWorkspaceId(7L);
        row.setAgentId(3L);
        row.setOwnerKey("owner-7");
        row.setMemoryType(type);
        row.setMemoryKey(key);
        row.setContent(content);
        row.setContentHash("old-" + version);
        row.setTokenEstimate(tokens);
        row.setVersionNo(version);
        row.setStatus(MemoryWriteGovernanceService.STATUS_APPLIED);
        row.setDeleted(0);
        return row;
    }
}
