package vip.newsclaw.skill.workspace.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Helpers for the bundle file buckets that mirror into the canonical
 * {@code mate_skill_file} store ({@code scripts/}, {@code references/}
 * and {@code templates/}).
 *
 * <p>Shared by the bundled-skill startup sync, the skill file syncer's
 * classpath backfill, and the admin file editor so all agree on which
 * paths are DB-persisted and how bundle contents are read into memory.
 */
public final class SkillBundleFiles {

    private static final Logger log = LoggerFactory.getLogger(SkillBundleFiles.class);

    /** Path prefixes of the buckets persisted to {@code mate_skill_file}. */
    public static final List<String> DB_BUCKET_PREFIXES = List.of("scripts/", "references/", "templates/");

    private SkillBundleFiles() {
    }

    /** True when the workspace-relative path belongs to a DB-persisted bucket. */
    public static boolean isDbEligible(String relativePath) {
        if (relativePath == null) return false;
        for (String prefix : DB_BUCKET_PREFIXES) {
            if (relativePath.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Read every DB-eligible bundle file ({@link #DB_BUCKET_PREFIXES})
     * into memory, keyed by workspace-relative path (the key shape
     * {@code SkillFileService#applyBundleFiles} expects). Only valid UTF-8
     * text without NUL bytes is persisted: bundles may also contain binary
     * PDF/image/font assets, which remain on disk but cannot be stored in a
     * database text column. Iteration order follows
     * {@link SkillBundleSource#assets()} enumeration order.
     */
    public static Map<String, String> readDbEligible(SkillBundleSource source) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (SkillBundleSource.BundleAsset asset : source.assets()) {
            String path = asset.relativePath();
            if (!isDbEligible(path)) continue;
            try (InputStream is = asset.open().get()) {
                Optional<String> content = decodeUtf8Text(is.readAllBytes());
                if (content.isPresent()) {
                    files.put(path, content.get());
                } else {
                    log.warn("Skipping non-text bundled skill asset from {}: {}",
                            source.origin(), path);
                }
            }
        }
        return files;
    }

    /**
     * Decode content that is safe for the canonical text store.
     *
     * <p>Java's {@code new String(bytes, UTF_8)} silently replaces malformed
     * byte sequences, and PostgreSQL rejects NUL even when it occurs in an
     * otherwise valid UTF-8 sequence. Decode with errors reported so callers
     * can keep binary assets on the filesystem instead of corrupting or
     * failing a database write.
     */
    public static Optional<String> decodeUtf8Text(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.of("");
        }
        for (byte value : bytes) {
            if (value == 0) {
                return Optional.empty();
            }
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
