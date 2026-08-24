package vip.mate.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.news.model.AiNewsCaptureAttemptEntity;
import vip.mate.news.model.AiNewsCaptureStatus;
import vip.mate.news.repository.AiNewsCaptureAttemptMapper;

import java.time.LocalDateTime;
import java.util.List;

/** Persists every official-source capture outcome in an independent transaction. */
@Service
@RequiredArgsConstructor
public class AiNewsCaptureAttemptService {

    private final AiNewsCaptureAttemptMapper mapper;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiNewsCaptureAttemptEntity record(Long workspaceId, Long eventId,
                                             String sourceUrl, String finalUrl,
                                             AiNewsCaptureStatus status, String error,
                                             Integer httpStatus, List<String> redirectChain) {
        LocalDateTime now = LocalDateTime.now();
        AiNewsCaptureAttemptEntity row = new AiNewsCaptureAttemptEntity();
        row.setWorkspaceId(workspaceId);
        row.setEventId(eventId);
        row.setSourceUrl(trim(sourceUrl, 4096));
        row.setFinalUrl(trim(finalUrl, 4096));
        row.setCaptureStatus(status.token());
        row.setCaptureError(trim(error, 2000));
        row.setHttpStatus(httpStatus);
        row.setCaptureMethod("READ_ONLY_HTTP");
        row.setRedirectChainJson(json(redirectChain));
        row.setAttemptedAt(now);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setDeleted(0);
        mapper.insert(row);
        return row;
    }

    public List<AiNewsCaptureAttemptEntity> list(Long workspaceId, Long eventId) {
        return mapper.selectList(new LambdaQueryWrapper<AiNewsCaptureAttemptEntity>()
                .eq(AiNewsCaptureAttemptEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsCaptureAttemptEntity::getEventId, eventId)
                .eq(AiNewsCaptureAttemptEntity::getDeleted, 0)
                .orderByDesc(AiNewsCaptureAttemptEntity::getAttemptedAt));
    }

    private String json(List<String> redirects) {
        try {
            return objectMapper.writeValueAsString(redirects == null ? List.of() : redirects);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
