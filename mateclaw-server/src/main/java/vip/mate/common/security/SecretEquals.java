package vip.mate.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 常数时间字符串比较工具（RFC-025 Change 2）。
 *
 * <p>用于比较秘钥 / 签名 / 会话 token 等敏感字符串，避免 {@link String#equals} 的字符级短路
 * 特征被 timing 攻击利用推断前缀。</p>
 *
 * <p>实现层统一 UTF-8 编码到 {@code byte[]} 后调用 {@link MessageDigest#isEqual(byte[], byte[])}
 * ——该方法在 JDK 6+ 即保证线性时间不短路（即使长度不等也是返回 false 而非抛异常）。
 * null 被视为空数组，与非 null / 非空比较结果为 false。</p>
 *
 * <p>使用场景：
 * <ul>
 *   <li>webhook 签名比对</li>
 *   <li>MCP / HTTP bearer 校验</li>
 *   <li>会话 token / context_token 比较</li>
 *   <li>后续 RFC（sanitizer / skill hub 签名）中复用</li>
 * </ul></p>
 */
public final class SecretEquals {

    private SecretEquals() {}

    /**
     * 常数时间比较两个字符串（视为 UTF-8 字节数组）。
     *
     * @return true 当且仅当两者非 null 且字节内容相等；任一为 null 且另一非空视为不等
     */
    public static boolean equals(String a, String b) {
        byte[] ba = (a == null) ? new byte[0] : a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = (b == null) ? new byte[0] : b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /** 字节数组版本：调用方已自行编码时可直接使用。 */
    public static boolean equals(byte[] a, byte[] b) {
        if (a == null) a = new byte[0];
        if (b == null) b = new byte[0];
        return MessageDigest.isEqual(a, b);
    }
}
