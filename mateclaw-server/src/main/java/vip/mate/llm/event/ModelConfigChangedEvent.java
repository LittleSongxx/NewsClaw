package vip.mate.llm.event;

/**
 * 模型配置变更事件。
 * <p>
 * 用于在默认模型或模型列表发生变化后刷新运行时 Agent 缓存，
 * 使聊天调用能够立即切换到最新的数据库默认模型。
 */
public record ModelConfigChangedEvent(String reason) {
}
