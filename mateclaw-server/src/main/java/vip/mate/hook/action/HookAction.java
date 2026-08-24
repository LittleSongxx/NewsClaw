package vip.mate.hook.action;

import vip.mate.hook.event.MateHookEvent;

/**
 * Hook 动作 SPI（sealed）。
 *
 * <p>每个 hook 声明一个 action：触发时由 {@code HookDispatcher} 传入事件对象，action 负责执行副作用。
 * sealed 锁定 4 种内置实现，避免运行时反射插件；自定义扩展通过 {@link BuiltinAction} 的 builtin op
 * 注册机制接入，见 {@link BuiltinAction}。</p>
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>execute 必须在 {@link #timeoutMillis()} 内返回，超时由派发器外部中断</li>
 *   <li>失败时应返回 {@link HookResult#failed}，<b>不要抛异常</b>（派发器会吞，但统计不准）</li>
 *   <li>默认 {@link #failOpen()} = true：失败永不传染主调用链</li>
 * </ul></p>
 */
public sealed interface HookAction
        permits BuiltinAction, HttpAction, ShellAction, ChannelMessageAction {

    /** 策略类型标识，用于序列化持久化。 */
    Kind kind();

    /** 执行副作用，返回结果供审计。 */
    HookResult execute(MateHookEvent event, HookContext ctx);

    /** 单次执行超时毫秒数；超过即视为 TIMEOUT。 */
    default long timeoutMillis() { return 3_000L; }

    /** 是否 fail-open（失败时吞掉）；默认 true，主链路零影响。 */
    default boolean failOpen() { return true; }

    /** 预留：future 调用前自检（如 HttpAction 检查域名白名单）。 */
    default void validate() { }

    enum Kind { BUILTIN, HTTP, SHELL, CHANNEL_MESSAGE }
}
