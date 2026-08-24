package vip.mate.hook.action;

import vip.mate.hook.event.MateHookEvent;

/**
 * 渠道消息动作占位（RFC-017 M3+ 实装；需接 ChannelMessageRouter）。
 *
 * <p>语义：hook 触发时往指定 channel（如"发一条 feishu 通知"）推消息。当前仅保留
 * sealed permits 形态，执行返回 BLOCKED。</p>
 */
public final class ChannelMessageAction implements HookAction {

    private final String channelType;
    private final String messageTemplate;

    public ChannelMessageAction(String channelType, String messageTemplate) {
        this.channelType = channelType;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public Kind kind() { return Kind.CHANNEL_MESSAGE; }

    @Override
    public HookResult execute(MateHookEvent event, HookContext ctx) {
        return HookResult.blocked("ChannelMessageAction not yet wired to ChannelMessageRouter");
    }
}
