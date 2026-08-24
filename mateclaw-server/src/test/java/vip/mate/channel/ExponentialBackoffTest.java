package vip.mate.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC-024 Change 5: ExponentialBackoff 新增 jitter 字段的行为验证（含向后兼容）。 */
class ExponentialBackoffTest {

    @Test
    @DisplayName("默认构造 jitter=0，行为与旧版本完全一致（纯指数递增）")
    void defaultConstructorHasNoJitter() {
        var b = new ExponentialBackoff();
        assertEquals(0.0, b.getJitter(), 0.0001);
        assertEquals(2000L, b.nextDelayMs());
        assertEquals(4000L, b.nextDelayMs());
        assertEquals(8000L, b.nextDelayMs());
        assertEquals(16000L, b.nextDelayMs());
        assertEquals(30000L, b.nextDelayMs());   // 触顶 maxDelay
        assertEquals(30000L, b.nextDelayMs());
    }

    @Test
    @DisplayName("4-arg 旧构造等价 jitter=0（向后兼容）")
    void fourArgLegacyConstructorZeroJitter() {
        var b = new ExponentialBackoff(2000, 30000, 2.0, -1);
        assertEquals(0.0, b.getJitter(), 0.0001);
    }

    @Test
    @DisplayName("jitter=0.2 时延迟在 ±20% 范围内波动")
    void jitterRandomWithinConfiguredRange() {
        var b = new ExponentialBackoff(1000, 60000, 2.0, -1, 0.2);
        // 第一次 nextDelayMs：base=1000，jitter ±20%，期望 [800, 1200]
        for (int trial = 0; trial < 50; trial++) {
            b.reset();
            long d = b.nextDelayMs();
            assertTrue(d >= 800 && d <= 1200, "expected 800..1200, got " + d + " (trial " + trial + ")");
        }
    }

    @Test
    @DisplayName("jitter 不会让延迟溢出到超过 maxDelayMs")
    void jitterClampedToMaxDelay() {
        var b = new ExponentialBackoff(10000, 15000, 2.0, -1, 0.5);
        for (int i = 0; i < 20; i++) {
            long d = b.nextDelayMs();
            assertTrue(d <= 15000, "delay should never exceed max 15000, got " + d);
        }
    }

    @Test
    @DisplayName("jitter 非负（偶尔随机会让 base 变成负数，需夹到 0）")
    void jitterNeverNegative() {
        // 小 initial + 大 jitter，理论上可能产生负数，应夹到 0
        var b = new ExponentialBackoff(10, 100, 2.0, -1, 0.8);
        for (int i = 0; i < 50; i++) {
            b.reset();
            assertTrue(b.nextDelayMs() >= 0);
        }
    }

    @Test
    @DisplayName("reset() 清零后 nextDelayMs 从 initial 再开始")
    void resetRestartsFromInitial() {
        var b = new ExponentialBackoff(1000, 10000, 2.0, -1, 0.0);
        b.nextDelayMs(); b.nextDelayMs(); b.nextDelayMs();
        assertTrue(b.getAttempts() > 0);
        b.reset();
        assertEquals(0, b.getAttempts());
        assertEquals(1000L, b.nextDelayMs());
    }

    @Test
    @DisplayName("jitter 参数被夹到 [0, 1)（防止恶意配置）")
    void jitterClampedToValidRange() {
        var bNeg = new ExponentialBackoff(1000, 10000, 2.0, -1, -0.5);
        assertEquals(0.0, bNeg.getJitter(), 0.0001);

        var bTooBig = new ExponentialBackoff(1000, 10000, 2.0, -1, 1.5);
        assertTrue(bTooBig.getJitter() < 1.0);
    }
}
