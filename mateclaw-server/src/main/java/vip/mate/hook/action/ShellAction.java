package vip.mate.hook.action;

import vip.mate.hook.event.MateHookEvent;

/**
 * Shell 动作占位（RFC-017 M3+ 实装；必须与 RFC-006 沙箱联动）。
 *
 * <p>此处仅保留 sealed permits 的形态，{@link #execute} 直接返回 BLOCKED，
 * 提醒运维当前环境未启用沙箱；真实执行路径会在 RFC-006 落地后启用。</p>
 */
public final class ShellAction implements HookAction {

    private final String command;

    public ShellAction(String command) {
        this.command = command;
    }

    @Override
    public Kind kind() { return Kind.SHELL; }

    @Override
    public HookResult execute(MateHookEvent event, HookContext ctx) {
        return HookResult.blocked("ShellAction requires RFC-006 sandbox; not yet enabled");
    }
}
