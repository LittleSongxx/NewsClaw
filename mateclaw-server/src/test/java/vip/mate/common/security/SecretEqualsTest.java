package vip.mate.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC-025 Change 2 常数时间秘钥比较的正确性测试（不测 timing，测正确性）。 */
class SecretEqualsTest {

    @Test
    @DisplayName("相等字符串返回 true")
    void equalStringsMatch() {
        assertTrue(SecretEquals.equals("abc123", "abc123"));
        assertTrue(SecretEquals.equals("", ""));
    }

    @Test
    @DisplayName("不等字符串返回 false，含不同长度、前缀差异、后缀差异")
    void unequalReturnsFalse() {
        assertFalse(SecretEquals.equals("abc123", "abc124"));
        assertFalse(SecretEquals.equals("abc", "abcd"));
        assertFalse(SecretEquals.equals("xabc", "yabc"));
    }

    @Test
    @DisplayName("null 视为空数组，任一 null 与非空字符串比较返回 false")
    void nullSafety() {
        assertTrue(SecretEquals.equals((String) null, (String) null));
        assertTrue(SecretEquals.equals((String) null, ""));
        assertFalse(SecretEquals.equals((String) null, "x"));
        assertFalse(SecretEquals.equals("x", (String) null));
    }

    @Test
    @DisplayName("UTF-8 编码一致性：中文等值字符串返回 true")
    void utf8ConsistencyForCjk() {
        assertTrue(SecretEquals.equals("秘钥", "秘钥"));
        assertFalse(SecretEquals.equals("秘钥", "秘密"));
    }

    @Test
    @DisplayName("字节数组版本与字符串版本结果一致")
    void byteArrayOverloadConsistent() {
        assertTrue(SecretEquals.equals(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
        assertFalse(SecretEquals.equals(new byte[]{1, 2, 3}, new byte[]{1, 2, 4}));
        assertFalse(SecretEquals.equals(new byte[]{1, 2, 3}, new byte[]{1, 2, 3, 4}));
        assertTrue(SecretEquals.equals((byte[]) null, (byte[]) null));
    }
}
