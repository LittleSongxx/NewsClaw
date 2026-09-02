package vip.newsclaw.news.service;

import vip.newsclaw.news.model.AiNewsEvidenceRelation;

/** Runtime boundary separating a model's first-pass label from an attested relation. */
public final class AiNewsRelationAttestation {

    public static final String MODEL = "MODEL";
    public static final String HUMAN = "HUMAN";
    public static final String DETERMINISTIC_EXTRACTIVE = "DETERMINISTIC_EXTRACTIVE";
    public static final String UNKNOWN = "UNKNOWN";

    private AiNewsRelationAttestation() {
    }

    public static boolean isVerificationAttested(String origin) {
        return HUMAN.equalsIgnoreCase(origin) || DETERMINISTIC_EXTRACTIVE.equalsIgnoreCase(origin);
    }

    public static boolean isExactExtractiveEntailment(String claim, String quote,
                                                       AiNewsEvidenceRelation relation) {
        if (relation != AiNewsEvidenceRelation.ENTAILS) return false;
        String normalizedClaim = AiNewsSourceDocumentParser.normalizeText(claim);
        String normalizedQuote = AiNewsSourceDocumentParser.normalizeText(quote);
        return !normalizedClaim.isBlank() && normalizedClaim.equals(normalizedQuote);
    }
}
