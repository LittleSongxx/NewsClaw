package vip.mate.external.effect;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the durable outbox-style ledger used before a Cron
 * result crosses the process boundary into Feishu or another IM platform.
 */
class ExternalEffectServiceTest {

    private ExternalEffectMapper mapper;
    private ExternalEffectService service;
    private AtomicReference<ExternalEffectEntity> inserted;

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ExternalEffectEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ExternalEffectMapper.class);
        service = new ExternalEffectService(mapper);
        inserted = new AtomicReference<>();
        when(mapper.insert(any(ExternalEffectEntity.class))).thenAnswer(invocation -> {
            ExternalEffectEntity entity = invocation.getArgument(0);
            entity.setId(8101L);
            inserted.set(entity);
            return 1;
        });
    }

    @Test
    @DisplayName("首次 claim 取得发送权，并写入 workspace 级幂等键")
    void firstClaimIsAcquired() {
        when(mapper.selectOne(any())).thenReturn(null);

        ExternalEffectService.ClaimResult claim = service.claim(request(7L, "feishu:run-1"));

        assertTrue(claim.acquired());
        assertEquals(ExternalEffectService.ClaimStatus.ACQUIRED, claim.status());
        assertNotNull(claim.ownerToken());
        assertEquals(7L, inserted.get().getWorkspaceId());
        assertEquals("IN_PROGRESS", inserted.get().getStatus());
        assertEquals(1, inserted.get().getAttemptCount());
    }

    @Test
    @DisplayName("已成功的外部副作用不会再次取得发送权")
    void succeededEffectIsNotResent() {
        ExternalEffectEntity succeeded = effect(7L, "feishu:run-1", "SUCCEEDED", LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(succeeded);

        ExternalEffectService.ClaimResult claim = service.claim(request(7L, "feishu:run-1"));

        assertEquals(ExternalEffectService.ClaimStatus.ALREADY_SUCCEEDED, claim.status());
        assertFalse(claim.acquired());
        assertNull(claim.ownerToken());
        verify(mapper, never()).insert(any(ExternalEffectEntity.class));
        verify(mapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("仍在 lease 内的发送不重发，避免不确定外部状态下重复投递")
    void activeClaimIsNotResent() {
        ExternalEffectEntity inProgress = effect(7L, "feishu:run-1", "IN_PROGRESS", LocalDateTime.now().minusSeconds(60));
        when(mapper.selectOne(any())).thenReturn(inProgress);

        ExternalEffectService.ClaimResult claim = service.claim(request(7L, "feishu:run-1"));

        assertEquals(ExternalEffectService.ClaimStatus.IN_PROGRESS, claim.status());
        assertFalse(claim.acquired());
        verify(mapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("过期 lease 可以由恢复任务重新取得，但保留原记录和尝试次数")
    void staleClaimCanBeRetried() {
        ExternalEffectEntity stale = effect(7L, "feishu:run-1", "IN_PROGRESS", LocalDateTime.now().minusSeconds(301));
        stale.setId(8101L);
        stale.setAttemptCount(1);
        ExternalEffectEntity claimed = effect(7L, "feishu:run-1", "IN_PROGRESS", LocalDateTime.now());
        claimed.setId(8101L);
        claimed.setAttemptCount(2);
        when(mapper.selectOne(any())).thenReturn(stale);
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(mapper.selectById(8101L)).thenReturn(claimed);

        ExternalEffectService.ClaimResult claim = service.claim(request(7L, "feishu:run-1"));

        assertEquals(ExternalEffectService.ClaimStatus.RETRY_ACQUIRED, claim.status());
        assertTrue(claim.acquired());
        assertEquals(2, claim.effect().getAttemptCount());
        verify(mapper, times(1)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("完成标记带 owner token 条件，旧 worker 不能覆盖新 owner")
    @SuppressWarnings("unchecked")
    void completionUsesOwnerTokenCompareAndSet() {
        when(mapper.selectOne(any())).thenReturn(null);
        ExternalEffectService.ClaimResult claim = service.claim(request(7L, "feishu:run-1"));
        AtomicReference<LambdaUpdateWrapper<ExternalEffectEntity>> update = new AtomicReference<>();
        when(mapper.update(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            update.set(invocation.getArgument(1));
            return 1;
        });

        service.markSucceeded(claim, "chat-1", "{\"message_id\":\"m1\"}");

        String sql = update.get().getSqlSegment();
        assertTrue(sql.contains("ownerToken") || sql.contains("owner_token"), sql);
        assertTrue(update.get().getParamNameValuePairs().containsValue(claim.ownerToken()));
        assertTrue(update.get().getParamNameValuePairs().containsValue("SUCCEEDED"));
    }

    @Test
    @DisplayName("相同外部键在不同 workspace 保持独立")
    void workspaceIsPartOfClaimScope() {
        ExternalEffectEntity workspaceOne = effect(1L, "feishu:run-1", "SUCCEEDED", LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(workspaceOne, (ExternalEffectEntity) null);

        ExternalEffectService.ClaimResult first = service.claim(request(1L, "feishu:run-1"));
        ExternalEffectService.ClaimResult second = service.claim(request(2L, "feishu:run-1"));

        assertEquals(ExternalEffectService.ClaimStatus.ALREADY_SUCCEEDED, first.status());
        assertTrue(second.acquired());
        assertEquals(2L, inserted.get().getWorkspaceId());
    }

    private ExternalEffectService.EffectRequest request(Long workspaceId, String key) {
        return new ExternalEffectService.EffectRequest(
                workspaceId, "CRON_CHANNEL_DELIVERY", key, "cron_job_run", "42",
                "feishu-chat", "digest", "{\"kind\":\"cron\"}");
    }

    private ExternalEffectEntity effect(Long workspaceId, String key, String status, LocalDateTime startedAt) {
        ExternalEffectEntity entity = new ExternalEffectEntity();
        entity.setId(8100L);
        entity.setWorkspaceId(workspaceId);
        entity.setEffectType("CRON_CHANNEL_DELIVERY");
        entity.setIdempotencyKey(key);
        entity.setStatus(status);
        entity.setAttemptCount(1);
        entity.setStartedAt(startedAt);
        entity.setDeleted(0);
        return entity;
    }
}
