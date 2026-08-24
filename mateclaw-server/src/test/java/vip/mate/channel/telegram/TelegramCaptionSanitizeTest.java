package vip.mate.channel.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC-025 Change 4：入站 caption / text 净化单测。 */
class TelegramCaptionSanitizeTest {

    @Test
    @DisplayName("空 / null 直接原样返回")
    void emptyInputs() {
        assertNull(TelegramChannelAdapter.sanitizeInboundText(null));
        assertEquals("", TelegramChannelAdapter.sanitizeInboundText(""));
    }

    @Test
    @DisplayName("纯文本 / 中文 / emoji 不应被误杀")
    void preservesNormalText() {
        assertEquals("Hello world", TelegramChannelAdapter.sanitizeInboundText("Hello world"));
        assertEquals("你好，世界", TelegramChannelAdapter.sanitizeInboundText("你好，世界"));
        assertEquals("日本語テスト", TelegramChannelAdapter.sanitizeInboundText("日本語テスト"));
        assertEquals("한국어", TelegramChannelAdapter.sanitizeInboundText("한국어"));
    }

    @Test
    @DisplayName("控制字符 / ZWSP / 非可打印字节被剥掉")
    void stripsNonPrintable() {
        // \u0000 NUL, \u0007 BEL, \u200B ZWSP
        String dirty = "a\u0000b\u0007c\u200Bd";
        String clean = TelegramChannelAdapter.sanitizeInboundText(dirty);
        assertEquals("abcd", clean, "got: " + clean);
    }

    @Test
    @DisplayName("超长字符串被截断到 4096 + truncation 标记")
    void longInputTruncated() {
        String huge = "x".repeat(5000);
        String result = TelegramChannelAdapter.sanitizeInboundText(huge);
        assertTrue(result.length() <= 4096 + 20, "expected ≤ 4116 chars, got " + result.length());
        assertTrue(result.endsWith("...[truncated]"));
    }

    @Test
    @DisplayName("模拟 .epub caption 泄露二进制 → 净化后可控大小")
    void simulatedEpubBinaryCaption() {
        // 构造类似 .epub 元数据：混合 UTF-8 文本 + 非可打印字节
        StringBuilder dirty = new StringBuilder();
        dirty.append("Book Title\u0000PK\u0003\u0004");   // ZIP 魔数 + 文件头
        for (int i = 0; i < 1000; i++) {
            dirty.append((char) (i % 32));   // 大量控制字符
        }
        dirty.append(" author:Foo");
        String clean = TelegramChannelAdapter.sanitizeInboundText(dirty.toString());
        // 净化结果不含任何控制字符
        assertFalse(clean.matches(".*[\\x00-\\x1F].*"),
                "cleaned should have no control bytes: " + clean);
        // 但保留了实际文本
        assertTrue(clean.contains("Book Title"));
        assertTrue(clean.contains("author:Foo"));
    }
}
