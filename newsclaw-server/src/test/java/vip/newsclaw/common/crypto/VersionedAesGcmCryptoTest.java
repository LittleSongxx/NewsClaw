package vip.newsclaw.common.crypto;

import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class VersionedAesGcmCryptoTest {

    private static final String KEY = "TestKey-1234567";

    @Test
    void roundTripUsesRandomAuthenticatedEnvelope() {
        VersionedAesGcmCrypto crypto = new VersionedAesGcmCrypto(KEY, "test-purpose");

        String first = crypto.encrypt("secret-value");
        String second = crypto.encrypt("secret-value");

        assertTrue(first.startsWith(VersionedAesGcmCrypto.V1_PREFIX));
        assertNotEquals(first, second, "a fresh GCM nonce is required for every write");
        assertEquals("secret-value", crypto.decrypt(first));
        assertEquals("secret-value", crypto.decrypt(second));
    }

    @Test
    void legacyHutoolCiphertextRemainsReadable() {
        byte[] legacyKey = Arrays.copyOf(KEY.getBytes(StandardCharsets.UTF_8), 16);
        String legacy = SecureUtil.aes(legacyKey).encryptHex("legacy-secret");

        assertEquals("legacy-secret",
                new VersionedAesGcmCrypto(KEY, "test-purpose").decrypt(legacy));
    }

    @Test
    void tamperingAndCrossPurposeSubstitutionAreRejected() {
        VersionedAesGcmCrypto source = new VersionedAesGcmCrypto(KEY, "source");
        String encrypted = source.encrypt("secret-value");
        byte[] envelope = Base64.getDecoder().decode(
                encrypted.substring(VersionedAesGcmCrypto.V1_PREFIX.length()));
        envelope[envelope.length - 1] ^= 1;
        String tampered = VersionedAesGcmCrypto.V1_PREFIX
                + Base64.getEncoder().encodeToString(envelope);

        assertThrows(IllegalArgumentException.class, () -> source.decrypt(tampered));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedAesGcmCrypto(KEY, "different-purpose").decrypt(encrypted));
    }
}
