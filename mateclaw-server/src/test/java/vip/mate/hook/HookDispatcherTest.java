package vip.mate.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.hook.action.BuiltinAction;
import vip.mate.hook.action.HookContext;
import vip.mate.hook.action.HookResult;
import vip.mate.hook.event.AgentEvent;
import vip.mate.hook.event.MateHookEvent;
import vip.mate.hook.event.ToolEvent;
import vip.mate.hook.model.HookEntity;
import vip.mate.hook.repository.HookRunMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** RFC-017 HookDispatcher 的核心行为与性能约束单测。 */
class HookDispatcherTest {

    private static HookProperties props() {
        var p = new HookProperties();
        p.setEnabled(true);
        p.setGlobalRateLimit(100);
        p.setGlobalConcurrency(8);
        p.setDispatchDeadline(Duration.ofMillis(500));
        p.getAudit().setEnabled(false);   // 单测关审计，避免 Mapper 交互
        return p;
    }

    private static HookEntity hook(long id, String type) {
        var h = new HookEntity();
        h.setId(id);
        h.setName("h-" + id);
        h.setEnabled(true);
        h.setEventType(type);
        h.setActionKind("BUILTIN");
        h.setRateLimitPerMin(6000);
        h.setTimeoutMs(1000);
        return h;
    }

    /** 记录调用次数的 BuiltinAction 子类（BuiltinAction 已改为 non-sealed）。 */
    static class CountingAction extends BuiltinAction {
        final AtomicInteger counter;
        CountingAction(AtomicInteger counter) {
            super("log.info", "test");
            this.counter = counter;
        }
        @Override
        public HookResult execute(MateHookEvent event, HookContext ctx) {
            counter.incrementAndGet();
            return HookResult.success(0L);
        }
    }

    /** 可阻塞的 BuiltinAction 子类，用于超时测试。 */
    static class HangingAction extends BuiltinAction {
        final CountDownLatch entered;
        HangingAction(CountDownLatch entered) {
            super("log.info", "hang");
            this.entered = entered;
        }
        @Override
        public HookResult execute(MateHookEvent event, HookContext ctx) {
            entered.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return HookResult.success(0L);
        }
    }

    /** 可测量延迟的 BuiltinAction 子类，用于并发测试。 */
    static class SleepingAction extends BuiltinAction {
        final AtomicInteger counter;
        final CountDownLatch latch;
        SleepingAction(AtomicInteger counter, CountDownLatch latch) {
            super("log.info", "sleep");
            this.counter = counter;
            this.latch = latch;
        }
        @Override
        public HookResult execute(MateHookEvent event, HookContext ctx) {
            try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            counter.incrementAndGet();
            latch.countDown();
            return HookResult.success(5L);
        }
    }

    @Test
    @DisplayName("派发器成功把事件发给匹配 hook 的 action")
    void dispatchInvokesMatchingAction() {
        AtomicInteger invoked = new AtomicInteger();
        HookMatch match = new HookMatch(hook(1, "agent:end"), new CountingAction(invoked));

        var registry = mock(HookRegistry.class);
        when(registry.match("agent:end")).thenReturn(List.of(match));
        when(registry.match("tool:after")).thenReturn(List.of());

        var dispatcher = new HookDispatcher(registry, mock(HookRunMapper.class), props());

        dispatcher.onEvent(AgentEvent.of("end", 1L, "trace", 3, Map.of()));
        dispatcher.onEvent(ToolEvent.of("after", "shell", 1L, "trace", Map.of()));

        assertTrue(waitFor(() -> invoked.get() == 1, 2_000));
        dispatcher.shutdown(2);
    }

    @Test
    @DisplayName("并发派发：100 个事件在 global concurrency=8 下不崩 + 最终全部被调")
    void concurrentDispatchStaysWithinBudget() throws InterruptedException {
        int events = 100;
        AtomicInteger invoked = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(events);
        HookMatch match = new HookMatch(hook(1, "agent:end"), new SleepingAction(invoked, latch));

        var registry = mock(HookRegistry.class);
        when(registry.match("agent:end")).thenReturn(List.of(match));

        var dispatcher = new HookDispatcher(registry, mock(HookRunMapper.class), props());
        for (int i = 0; i < events; i++) {
            dispatcher.onEvent(AgentEvent.of("end", (long) i, "trace", 1, Map.of()));
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "all events should dispatch within 10s");
        assertEquals(events, invoked.get());
        dispatcher.shutdown(2);
    }

    @Test
    @DisplayName("超时保护：action 永久阻塞不会拖死派发器")
    void deadlineInterruptsHungAction() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        HookMatch match = new HookMatch(hook(1, "agent:end"), new HangingAction(entered));

        var registry = mock(HookRegistry.class);
        when(registry.match("agent:end")).thenReturn(List.of(match));

        var dispatcher = new HookDispatcher(registry, mock(HookRunMapper.class), props());
        long t0 = System.nanoTime();
        dispatcher.onEvent(AgentEvent.of("end", 1L, "trace", 1, Map.of()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        dispatcher.shutdown(3);    // deadline 500ms + 关闭 3s 足够
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsed < 4_000,
                "dispatcher shutdown should not be blocked by hung action (elapsed=" + elapsed + "ms)");
    }

    @Test
    @DisplayName("限速：超过 per-hook limit 被标 RATE_LIMITED，不调用 action")
    void rateLimiterSkipsExcess() throws InterruptedException {
        AtomicInteger invoked = new AtomicInteger();
        HookEntity e = hook(1, "agent:end");
        e.setRateLimitPerMin(3);
        HookMatch match = new HookMatch(e, new CountingAction(invoked));

        var registry = mock(HookRegistry.class);
        when(registry.match("agent:end")).thenReturn(List.of(match));

        var dispatcher = new HookDispatcher(registry, mock(HookRunMapper.class), props());
        for (int i = 0; i < 10; i++) {
            dispatcher.onEvent(AgentEvent.of("end", (long) i, "trace", 1, Map.of()));
        }
        assertTrue(waitFor(() -> invoked.get() >= 3, 2_000));
        Thread.sleep(300);
        assertTrue(invoked.get() <= 3, "invocations must not exceed 3 per minute, got " + invoked.get());
        dispatcher.shutdown(2);
    }

    @Test
    @DisplayName("enabled=false 时派发器零开销（不调用 registry）")
    void disabledHasZeroOverhead() {
        var registry = mock(HookRegistry.class);
        var p = props();
        p.setEnabled(false);
        var dispatcher = new HookDispatcher(registry, mock(HookRunMapper.class), p);
        dispatcher.onEvent(AgentEvent.of("end", 1L, "trace", 1, Map.of()));
        verify(registry, never()).match(any());
        dispatcher.shutdown(1);
    }

    // ===== 测试辅助 =====

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(10); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); return false;
            }
        }
        return cond.getAsBoolean();
    }
}
