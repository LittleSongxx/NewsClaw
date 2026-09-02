package vip.newsclaw.news.service;

import vip.newsclaw.news.model.AiNewsCategory;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fail-closed representation for the strict Agent write path.
 *
 * <p>The persisted card is derived from the one evidence-bound atomic claim;
 * free-form headline/summary prose therefore cannot smuggle additional facts
 * around the citation gate.</p>
 */
public final class AiNewsAtomicFactGuard {

    static final int MAX_ATOMIC_CLAIM_CHARS = 512;

    private AiNewsAtomicFactGuard() {
    }

    public static AtomicFact prepare(String category, List<String> entities, String claim,
                                     Instant windowStart) {
        String value = normalizeDisplay(claim);
        if (value.isBlank()) throw new IllegalArgumentException("claim is required for upsert");
        if (value.codePointCount(0, value.length()) < 8) {
            throw new IllegalArgumentException("claim must be a complete atomic fact, not a label");
        }
        if (value.length() > MAX_ATOMIC_CLAIM_CHARS) {
            throw new IllegalArgumentException("claim must be at most " + MAX_ATOMIC_CLAIM_CHARS
                    + " characters; split compound cards into atomic facts");
        }
        String canonicalCategory = AiNewsCategory.normalize(category);
        List<String> canonicalEntities = entities == null ? List.of() : entities.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(AiNewsAtomicFactGuard::normalizeDisplay).distinct()
                .sorted(Comparator.comparing(item -> item.toLowerCase(Locale.ROOT))).toList();
        String windowDay = windowStart == null ? "unknown-day"
                : windowStart.atZone(ZoneOffset.UTC).toLocalDate().toString();
        String keyMaterial = String.join("|", "atomic-v2", windowDay, canonicalCategory,
                canonicalEntities.stream().map(AiNewsAtomicFactGuard::fingerprintText)
                        .reduce((left, right) -> left + "," + right).orElse(""),
                fingerprintText(value));
        return new AtomicFact(value, value, canonicalCategory, canonicalEntities, keyMaterial);
    }

    static String fingerprintText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .trim();
    }

    private static String normalizeDisplay(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim();
    }

    public record AtomicFact(String title,
                             String summary,
                             String category,
                             List<String> entities,
                             String eventKeyMaterial) {
        public AtomicFact {
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
    }
}
