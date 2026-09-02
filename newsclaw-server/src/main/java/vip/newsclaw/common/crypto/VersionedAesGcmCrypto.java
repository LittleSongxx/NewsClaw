package vip.newsclaw.common.crypto;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Versioned authenticated encryption for application secrets.
 *
 * <p>New values use {@code enc:v1:base64(nonce || ciphertext || tag)} with a
 * fresh 96-bit nonce. Unprefixed values can still be decrypted with the old
 * Hutool AES/ECB format so existing installations migrate without a flag day.
 */
public final class VersionedAesGcmCrypto {

    public static final String V1_PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final byte[] associatedData;
    private final byte[] legacyKey;

    public VersionedAesGcmCrypto(String masterKey, String purpose) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("Encryption master key must not be blank");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Encryption purpose must not be blank");
        }
        byte[] raw = masterKey.getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(sha256(raw), "AES");
        this.associatedData = ("newsclaw:" + purpose + ":v1").getBytes(StandardCharsets.UTF_8);
        // Compatibility only: the historical format used the first/padded 16
        // master-key bytes with Hutool's default AES/ECB/PKCS5Padding mode.
        this.legacyKey = Arrays.copyOf(raw, 16);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return V1_PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypt a versioned value or an unprefixed legacy Hutool ciphertext.
     * Authentication failures for versioned values are never downgraded to the
     * legacy path.
     */
    public String decrypt(String stored) {
        if (stored == null) return null;
        if (!isVersioned(stored)) {
            AES legacyAes = SecureUtil.aes(legacyKey);
            return legacyAes.decryptStr(stored);
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(stored.substring(V1_PREFIX.length()));
            if (envelope.length < NONCE_BYTES + TAG_BITS / Byte.SIZE) {
                throw new GeneralSecurityException("Encrypted envelope is too short");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to authenticate encrypted secret", e);
        }
    }

    public static boolean isVersioned(String stored) {
        return stored != null && stored.startsWith(V1_PREFIX);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
