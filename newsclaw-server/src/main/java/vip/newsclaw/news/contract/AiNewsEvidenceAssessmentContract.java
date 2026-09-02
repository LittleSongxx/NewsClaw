package vip.newsclaw.news.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict model contract for semantic relations only; it contains no policy booleans. */
public final class AiNewsEvidenceAssessmentContract {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> ROOT_FIELDS = Set.of("relations");
    private static final Set<String> ITEM_FIELDS = Set.of("evidenceId", "relation", "confidence");

    private AiNewsEvidenceAssessmentContract() {
    }

    public static ParseResult parseExact(String content, Collection<String> expectedEvidenceIds) {
        if (content == null || content.isBlank()) {
            return ParseResult.invalid("assistant content is empty");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(content.trim());
            if (root == null || !root.isObject()) {
                return ParseResult.invalid("assistant content must be exactly one JSON object");
            }
            List<String> failures = new ArrayList<>();
            validateFields(root, ROOT_FIELDS, "assessment", failures);
            JsonNode relationsNode = root.get("relations");
            List<RelationAssessment> relations = new ArrayList<>();
            Set<String> observedIds = new LinkedHashSet<>();
            List<String> observedIdSequence = new ArrayList<>();
            if (relationsNode == null || !relationsNode.isArray() || relationsNode.isEmpty()) {
                failures.add("relations must be a nonempty array");
            } else if (relationsNode.size() > 100) {
                failures.add("relations must contain at most 100 items");
            } else {
                for (JsonNode item : relationsNode) {
                    if (!item.isObject()) {
                        failures.add("every relations item must be an object");
                        continue;
                    }
                    validateFields(item, ITEM_FIELDS, "relation item", failures);
                    String id = text(item, "evidenceId", failures);
                    String rawRelation = text(item, "relation", failures);
                    AiNewsEvidenceRelation relation = null;
                    try {
                        relation = AiNewsEvidenceRelation.from(rawRelation);
                        if (relation == AiNewsEvidenceRelation.UNKNOWN) {
                            failures.add("relation must not be unknown");
                        }
                    } catch (IllegalArgumentException e) {
                        failures.add("relation must be entails/contradicts/partial/unrelated/hedged");
                    }
                    Double confidence = number(item, "confidence", failures);
                    if (id != null && !observedIds.add(id)) {
                        failures.add("duplicate evidenceId: " + id);
                    }
                    if (id != null) observedIdSequence.add(id);
                    if (id != null && relation != null && relation != AiNewsEvidenceRelation.UNKNOWN
                            && confidence != null) {
                        relations.add(new RelationAssessment(id, relation, confidence));
                    }
                }
            }
            validateExpectedIds(expectedEvidenceIds, observedIds, observedIdSequence, failures);
            if (!failures.isEmpty()) {
                return new ParseResult(null, String.join("; ", failures));
            }
            return new ParseResult(new Assessment(List.copyOf(relations)), "");
        } catch (Exception e) {
            return ParseResult.invalid(conciseMessage(e));
        }
    }

    private static void validateExpectedIds(Collection<String> expectedEvidenceIds,
                                            Set<String> observedIds,
                                            List<String> observedIdSequence,
                                            List<String> failures) {
        if (expectedEvidenceIds == null) return;
        Set<String> expected = new LinkedHashSet<>();
        for (String raw : expectedEvidenceIds) {
            if (raw == null || raw.isBlank() || !raw.equals(raw.trim())) {
                failures.add("expectedEvidenceIds must contain only trimmed nonblank ids");
                continue;
            }
            if (!expected.add(raw)) failures.add("expectedEvidenceIds must not contain duplicates");
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(observedIds);
        Set<String> unexpected = new LinkedHashSet<>(observedIds);
        unexpected.removeAll(expected);
        if (!missing.isEmpty()) failures.add("missing evidence assessments: " + missing);
        if (!unexpected.isEmpty()) failures.add("unexpected evidence assessments: " + unexpected);
        if (missing.isEmpty() && unexpected.isEmpty()
                && expected.size() == observedIdSequence.size()
                && !List.copyOf(expected).equals(observedIdSequence)) {
            failures.add("relations must follow expectedEvidenceIds order");
        }
    }

    private static void validateFields(JsonNode node, Set<String> expected,
                                       String label, List<String> failures) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty()) failures.add(label + " missing fields: " + missing);
        if (!unexpected.isEmpty()) failures.add(label + " unexpected fields: " + unexpected);
    }

    private static String text(JsonNode node, String field, List<String> failures) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || !value.asText().equals(value.asText().trim())) {
            failures.add(field + " must be a trimmed nonblank string");
            return null;
        }
        return value.asText();
    }

    private static Double number(JsonNode node, String field, List<String> failures) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            failures.add(field + " must be a JSON number between 0 and 1");
            return null;
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < 0.0D || number > 1.0D) {
            failures.add(field + " must be a JSON number between 0 and 1");
            return null;
        }
        return number;
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "invalid JSON assessment";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    public record Assessment(List<RelationAssessment> relations) {
        public Assessment {
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record RelationAssessment(String evidenceId,
                                     AiNewsEvidenceRelation relation,
                                     double confidence) {
    }

    public record ParseResult(Assessment assessment, String failureReason) {
        static ParseResult invalid(String reason) {
            return new ParseResult(null, reason);
        }

        public boolean valid() {
            return assessment != null;
        }
    }
}
