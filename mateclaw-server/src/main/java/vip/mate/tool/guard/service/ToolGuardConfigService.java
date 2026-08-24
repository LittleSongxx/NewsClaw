package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.repository.ToolGuardConfigMapper;

import vip.mate.tool.guard.model.GuardSeverity;

import java.util.List;
import java.util.Set;

/**
 * 工具安全配置管理服务
 * <p>
 * 管理 mate_tool_guard_config 单行配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolGuardConfigService {

    private final ToolGuardConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 获取配置（不存在则创建默认配置）
     */
    public ToolGuardConfigEntity getConfig() {
        List<ToolGuardConfigEntity> configs = configMapper.selectList(
                new LambdaQueryWrapper<ToolGuardConfigEntity>().last("LIMIT 1"));
        if (configs.isEmpty()) {
            return createDefaultConfig();
        }
        return configs.get(0);
    }

    /**
     * 更新配置
     */
    public ToolGuardConfigEntity updateConfig(ToolGuardConfigEntity config) {
        ToolGuardConfigEntity existing = getConfig();
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getGuardScope() != null) existing.setGuardScope(config.getGuardScope());
        if (config.getGuardedToolsJson() != null) existing.setGuardedToolsJson(config.getGuardedToolsJson());
        if (config.getDeniedToolsJson() != null) existing.setDeniedToolsJson(config.getDeniedToolsJson());
        if (config.getFileGuardEnabled() != null) existing.setFileGuardEnabled(config.getFileGuardEnabled());
        if (config.getSensitivePathsJson() != null) existing.setSensitivePathsJson(config.getSensitivePathsJson());
        if (config.getAuditEnabled() != null) existing.setAuditEnabled(config.getAuditEnabled());
        if (config.getAuditMinSeverity() != null) existing.setAuditMinSeverity(config.getAuditMinSeverity());
        if (config.getAuditRetentionDays() != null) existing.setAuditRetentionDays(config.getAuditRetentionDays());
        configMapper.updateById(existing);
        // 通知 AgentService 刷新缓存（denied 工具列表变更需要重建 agent 的工具集）
        eventPublisher.publishEvent(new ToolGuardConfigChangedEvent(this));
        return existing;
    }

    /**
     * 配置变更事件，触发 agent 缓存刷新
     */
    public static class ToolGuardConfigChangedEvent extends org.springframework.context.ApplicationEvent {
        public ToolGuardConfigChangedEvent(Object source) {
            super(source);
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(getConfig().getEnabled());
    }

    public Set<String> getDeniedTools() {
        String json = getConfig().getDeniedToolsJson();
        return parseJsonSet(json);
    }

    public Set<String> getGuardedTools() {
        ToolGuardConfigEntity config = getConfig();
        if ("all".equals(config.getGuardScope())) return null; // null = all tools
        return parseJsonSet(config.getGuardedToolsJson());
    }

    public boolean isFileGuardEnabled() {
        return Boolean.TRUE.equals(getConfig().getFileGuardEnabled());
    }

    public List<String> getSensitivePaths() {
        String json = getConfig().getSensitivePathsJson();
        return parseJsonList(json);
    }

    // ==================== 审计配置 ====================

    public boolean isAuditEnabled() {
        return Boolean.TRUE.equals(getConfig().getAuditEnabled());
    }

    public GuardSeverity getAuditMinSeverity() {
        String s = getConfig().getAuditMinSeverity();
        if (s == null || s.isBlank()) return GuardSeverity.INFO;
        try {
            return GuardSeverity.valueOf(s);
        } catch (IllegalArgumentException e) {
            return GuardSeverity.INFO;
        }
    }

    public int getAuditRetentionDays() {
        Integer days = getConfig().getAuditRetentionDays();
        return days != null ? days : 90;
    }

    // ==================== 内部方法 ====================

    private ToolGuardConfigEntity createDefaultConfig() {
        ToolGuardConfigEntity config = new ToolGuardConfigEntity();
        config.setEnabled(true);
        config.setGuardScope("all");
        config.setFileGuardEnabled(true);
        configMapper.insert(config);
        log.info("[ToolGuardConfig] Created default config");
        return config;
    }

    private Set<String> parseJsonSet(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            return Set.copyOf(list);
        } catch (JsonProcessingException e) {
            log.warn("[ToolGuardConfig] Failed to parse JSON set: {}", e.getMessage());
            return Set.of();
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("[ToolGuardConfig] Failed to parse JSON list: {}", e.getMessage());
            return List.of();
        }
    }
}
