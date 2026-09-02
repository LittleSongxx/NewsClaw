package vip.newsclaw.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Transparent at-rest encryption for sensitive system settings (API keys,
 * WeChat Official Account app secret, etc.). Values are encrypted with
 * AES-256-GCM and stored as {@code enc:v1:<base64(iv||ciphertext||tag)>}.
 *
 * <p>Backward compatibility: {@link #decrypt} returns any value WITHOUT the
 * {@code enc:v1:} prefix verbatim, so legacy plaintext secrets keep working and
 * are transparently upgraded to ciphertext the next time they are saved.
 *
 * <p>Key source, in order:
 * <p>{@code NEWSCLAW_SETTING_KEY} is required for production. A random
 * process-local key is used only for an explicitly keyless development run, so
 * such a run cannot silently create ciphertext that looks production-safe.</p>
 */
@Slf4j
@Component
public class SettingCrypto {

    /** Version-tagged prefix so the format can evolve and be detected on read. */
    static final String PREFIX = "enc:v1:";
    private static final String ENV_KEY = "NEWSCLAW_SETTING_KEY";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SettingCrypto(@Value("${newsclaw.setting.key:}") String configuredKey) {
        String source = firstNonBlank(configuredKey, System.getenv(ENV_KEY));
        if (source == null || source.isBlank()) {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            source = Base64.getEncoder().encodeToString(ephemeral);
            log.warn("[SettingCrypto] No {} set; using a process-local development key. "
                    + "Production startup must provide a persistent high-entropy key.", ENV_KEY);
        }
        this.key = deriveKey(source);
    }

    /** Encrypt a plaintext value into the {@code enc:v1:} envelope. Blank in → blank out. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never persist a half-encrypted value; surface loudly instead.
            throw new IllegalStateException("Failed to encrypt sensitive setting", e);
        }
    }

    /**
     * Decrypt an {@code enc:v1:} value. Any value without the prefix is returned
     * unchanged (legacy plaintext), so reads never break during migration.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key or corrupt data — don't hand back ciphertext as if it were the secret.
            log.error("[SettingCrypto] Failed to decrypt a sensitive setting (wrong {} or corrupt "
                    + "value?). Returning empty.", ENV_KEY);
            return "";
        }
    }

    /** True if the value is already in the encrypted envelope. */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static SecretKeySpec deriveKey(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive setting encryption key", e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
