package vip.newsclaw.news.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared deterministic SHA-256 helpers for endpoint, item and representation identities. */
public final class NewsSourceHashing {

    private NewsSourceHashing() {
    }

    public static String sha256(String value) {
        return sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value == null ? new byte[0] : value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String shortHash(String value) {
        return sha256(value).substring(0, 24);
    }
}
