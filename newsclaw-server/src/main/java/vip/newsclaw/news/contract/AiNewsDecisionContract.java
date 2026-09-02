package vip.newsclaw.news.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, context-free contract for the AI-news terminal decision object.
 *
 * <p>This validates only JSON shape and internal field relationships. Whether
 * a decision agrees with the actual evidence remains a policy/evaluation
 * concern and deliberately is not inferred here.</p>
 */
public final class AiNewsDecisionContract {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final List<String> REQUIRED_FIELDS = List.of(
            "sourceTier", "verificationEligible", "citationAllowed", "claimQuoteSupported",
            "refusalIssued", "humanReviewRequested", "citationIds");
    private static final Set<String> REQUIRED_FIELD_SET = Set.copyOf(REQUIRED_FIELDS);
    private static final Set<String> SOURCE_TIERS = Set.of("official", "media", "community");
    private static final Set<String> EXPRESSION_IDENTIFIERS = Set.of(
            "a", "b", "c", "d", "e", "sourceTier", "verificationEligible",
            "citationAllowed", "claimQuoteSupported", "refusalIssued",
            "humanReviewRequested", "citationIds", "unresolvedConflict",
            "requestedCitationSupport", "trustedClaimSupport", "strongestTier",
            "verification", "citationSupport");
    private static final Set<String> ANNOTATION_WORDS = Set.of(
            "a", "b", "c", "d", "e", "and", "or", "not", "true", "false",
            "because", "since", "when", "if", "as", "is", "are", "equals",
            "equal", "this", "that", "citationAllowed", "verificationEligible",
            "claimQuoteSupported", "refusalIssued", "humanReviewRequested",
            "sourceTier", "citationIds", "official", "media", "community");
    private static final Pattern BOOLEAN_TOKEN = Pattern.compile("\\b(true|false)\\b");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^\\d+[.)]\\s*");
    private static final Pattern LETTER_PREFIX = Pattern.compile("^[A-Za-z][.)]\\s*");
    private static final Pattern SOURCE_VALUE = Pattern.compile(
            "^\\s*\\(?\\s*(?:[A-Za-z]\\s*=\\s*)?(?:\\\"(official|media|community)\\\"|"
                    + "'(official|media|community)'|(official|media|community))"
                    + "(?=\\s|$|[).,;:])");
    private static final Pattern BARE_ARRAY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    private AiNewsDecisionContract() {
    }

    /**
     * Parse exactly one JSON object. Generic JSON objects remain valid objects;
     * only objects that contain a decision field are subject to this contract.
     */
    public static ParseResult parseExactJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return new ParseResult(false, false, null, "assistant content is empty");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(content.trim());
            if (root == null || !root.isObject()) {
                return new ParseResult(false, false, null,
                        "assistant content must be exactly one JSON object");
            }
            DecisionValidation validation = validateDecisionObject(root);
            return new ParseResult(true, validation.decisionCandidate(), validation.decision(),
                    validation.failureReason());
        } catch (Exception e) {
            return new ParseResult(false, false, null, conciseMessage(e));
        }
    }

    /** Return a decision only when the entire input is a valid seven-field decision. */
    public static Optional<Decision> parseExactDecision(String content) {
        return Optional.ofNullable(parseExactJsonObject(content).decision());
    }

    /**
     * Bind an otherwise context-free decision to the request's exact citation
     * packet. This prevents a syntactically coherent model response from
     * authorizing an invented or wrong evidence id.
     */
    public static ContextValidation validateCitationContext(Decision decision,
                                                            Collection<String> allowedCitationIds,
                                                            String requestedCitationId) {
        if (decision == null) {
            return new ContextValidation(false, "AI-news decision is missing");
        }
        if (allowedCitationIds == null && (requestedCitationId == null || requestedCitationId.isBlank())) {
            return new ContextValidation(true, "");
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (allowedCitationIds != null) {
            for (String raw : allowedCitationIds) {
                if (raw == null || raw.isBlank() || !raw.equals(raw.trim())) {
                    return new ContextValidation(false,
                            "allowedCitationIds must contain only trimmed nonblank ids");
                }
                if (!allowed.add(raw)) {
                    return new ContextValidation(false, "allowedCitationIds must not contain duplicates");
                }
            }
        }
        String requested = requestedCitationId == null ? "" : requestedCitationId.trim();
        for (String citationId : decision.citationIds()) {
            if (!allowed.contains(citationId)) {
                return new ContextValidation(false,
                        "citation id is outside the request allowlist: " + citationId);
            }
        }
        if (decision.citationAllowed()) {
            if (requested.isBlank()) {
                return new ContextValidation(false,
                        "citationAllowed=true requires a requestedCitationId context");
            }
            if (!allowed.contains(requested)) {
                return new ContextValidation(false,
                        "requestedCitationId is outside the request allowlist");
            }
            if (!decision.citationIds().equals(List.of(requested))) {
                return new ContextValidation(false,
                        "citationIds must be exactly [requestedCitationId] when citationAllowed=true");
            }
        }
        return new ContextValidation(true, "");
    }

    /**
     * Extract the last complete, strict decision from a reasoning transcript.
     *
     * <p>Models normally finish their reasoning with a JSON object. Some
     * providers, however, put the final mechanical audit in a seven-line
     * assignment block and emit a stale JSON object on the terminal channel.
     * We support that second representation only after an explicit final
     * audit/recompute heading, and only when every field is present exactly
     * once and passes the same JSON contract used by the web channel.</p>
     */
    public static Optional<Decision> extractLastDecision(String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return Optional.empty();
        }

        LocatedDecision last = extractLastJsonDecision(thinking);
        LocatedDecision assignment = extractLastAssignmentDecision(thinking);
        if (assignment != null && (last == null || assignment.position() > last.position())) {
            last = assignment;
        }
        return last == null ? Optional.empty() : Optional.of(last.decision());
    }

    /** Scan balanced top-level JSON objects and retain the last valid decision. */
    private static LocatedDecision extractLastJsonDecision(String thinking) {
        LocatedDecision last = null;
        int objectStart = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < thinking.length(); index++) {
            char current = thinking.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) {
                    objectStart = index;
                }
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String candidate = thinking.substring(objectStart, index + 1);
                    Optional<Decision> parsed = parseExactDecision(candidate);
                    if (parsed.isPresent()) {
                        last = new LocatedDecision(parsed.get(), index + 1);
                    }
                    objectStart = -1;
                }
            }
        }
        return last;
    }

    /**
     * Parse assignment blocks from explicit final-audit sections only. The
     * line-oriented parser intentionally ignores prose such as
     * "the sourceTier is official"; an assignment must start a line (after a
     * list marker) and use an equals sign.
     */
    private static LocatedDecision extractLastAssignmentDecision(String thinking) {
        LocatedDecision last = null;
        for (Section section : findFinalSections(thinking)) {
            List<Assignment> assignments = parseAssignments(thinking, section.start(), section.end());
            if (assignments.isEmpty()) {
                continue;
            }
            List<Assignment> run = new ArrayList<>();
            Assignment previous = null;
            for (Assignment assignment : assignments) {
                if (previous != null
                        && !thinking.substring(previous.end(), assignment.start()).isBlank()) {
                    LocatedDecision candidate = parseAssignmentRun(run);
                    if (candidate != null && (last == null || candidate.position() > last.position())) {
                        last = candidate;
                    }
                    run = new ArrayList<>();
                }
                run.add(assignment);
                previous = assignment;
            }
            LocatedDecision candidate = parseAssignmentRun(run);
            if (candidate != null && (last == null || candidate.position() > last.position())) {
                last = candidate;
            }
        }
        return last;
    }

    private static LocatedDecision parseAssignmentRun(List<Assignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return null;
        }
        Map<String, Assignment> byField = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            if (!REQUIRED_FIELD_SET.contains(assignment.field())) {
                continue;
            }
            if (byField.putIfAbsent(assignment.field(), assignment) != null) {
                // A repeated field in one block is ambiguous. Do not silently
                // choose the last value, since that would turn a bad audit into
                // an apparently trustworthy decision.
                return null;
            }
        }
        if (byField.size() != REQUIRED_FIELDS.size()) {
            return null;
        }

        String sourceTier = parseAssignmentSource(byField.get("sourceTier").value());
        Boolean verificationEligible = parseAssignmentBoolean(
                byField.get("verificationEligible").value());
        Boolean citationAllowed = parseAssignmentBoolean(byField.get("citationAllowed").value());
        Boolean claimQuoteSupported = parseAssignmentBoolean(
                byField.get("claimQuoteSupported").value());
        Boolean refusalIssued = parseAssignmentBoolean(byField.get("refusalIssued").value());
        Boolean humanReviewRequested = parseAssignmentBoolean(
                byField.get("humanReviewRequested").value());
        List<String> citationIds = parseAssignmentCitationIds(byField.get("citationIds").value());
        if (sourceTier == null || verificationEligible == null || citationAllowed == null
                || claimQuoteSupported == null || refusalIssued == null
                || humanReviewRequested == null || citationIds == null) {
            return null;
        }

        ObjectNode root = STRICT_JSON.createObjectNode();
        root.put("sourceTier", sourceTier);
        root.put("verificationEligible", verificationEligible);
        root.put("citationAllowed", citationAllowed);
        root.put("claimQuoteSupported", claimQuoteSupported);
        root.put("refusalIssued", refusalIssued);
        root.put("humanReviewRequested", humanReviewRequested);
        var ids = root.putArray("citationIds");
        citationIds.forEach(ids::add);
        DecisionValidation validation = validateDecisionObject(root);
        if (validation.decision() == null) {
            return null;
        }
        Assignment last = assignments.get(assignments.size() - 1);
        return new LocatedDecision(validation.decision(), last.end());
    }

    private static List<Section> findFinalSections(String thinking) {
        List<Integer> starts = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= thinking.length()) {
            int lineEnd = thinking.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = thinking.length();
            }
            if (isFinalSectionHeading(thinking.substring(lineStart, lineEnd))) {
                starts.add(lineStart);
            }
            if (lineEnd == thinking.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : thinking.length();
            int contentStart = thinking.indexOf('\n', start);
            contentStart = contentStart < 0 ? thinking.length() : contentStart + 1;
            sections.add(new Section(contentStart, end));
        }
        return sections;
    }

    private static boolean isFinalSectionHeading(String line) {
        String heading = line == null ? "" : line.trim();
        heading = heading.replaceFirst("^(?:>+|#+)\\s*", "");
        heading = heading.replaceFirst("^(?:[-+*]\\s+|\\d+[.)]\\s+|[A-Za-z][.)]\\s+)+", "");
        heading = heading.replaceAll("^[*_`]+", "").replaceAll("[*_`]+$", "");
        heading = heading.trim().toLowerCase(Locale.ROOT);
        return heading.startsWith("final audit")
                || heading.startsWith("final mechanical audit")
                || heading.startsWith("final decision")
                || heading.startsWith("final determination")
                || heading.startsWith("final output")
                || heading.startsWith("final json")
                || heading.startsWith("output fields")
                || heading.startsWith("mapping to output fields")
                || heading.startsWith("map to output fields")
                || heading.startsWith("now map to output fields")
                || heading.startsWith("now let me map to output fields")
                || heading.startsWith("recompute")
                || heading.startsWith("recomputed fields")
                || heading.startsWith("decision recomputation")
                || heading.startsWith("最终审计")
                || heading.startsWith("最终决策")
                || heading.startsWith("最终输出")
                || heading.startsWith("重算");
    }

    private static List<Assignment> parseAssignments(String text, int start, int end) {
        List<Assignment> assignments = new ArrayList<>();
        Assignment pending = null;
        int lineStart = start;
        while (lineStart <= end) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > end) {
                lineEnd = end;
            }
            String line = text.substring(lineStart, lineEnd);
            Assignment parsed = parseAssignmentLine(line, lineStart, lineEnd);
            if (parsed != null) {
                if (pending != null) {
                    assignments.add(pending);
                }
                pending = parsed;
            } else if (pending != null && isAssignmentContinuation(pending.value(), line)) {
                String continuation = line.trim();
                String joined = pending.value().isBlank()
                        ? continuation
                        : pending.value().trim() + " " + continuation;
                pending = new Assignment(pending.field(), joined, pending.start(), lineEnd);
            } else if (pending != null) {
                assignments.add(pending);
                pending = null;
            }
            if (lineEnd >= end) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        if (pending != null) {
            assignments.add(pending);
        }
        return assignments;
    }

    private static Assignment parseAssignmentLine(String line, int lineStart, int lineEnd) {
        String rest = line == null ? "" : line.stripLeading();
        for (int count = 0; count < 6; count++) {
            if (rest.startsWith("-") || rest.startsWith("+") || rest.startsWith("*")
                    || rest.startsWith(">")) {
                rest = rest.substring(1).stripLeading();
                continue;
            }
            Matcher number = NUMBER_PREFIX.matcher(rest);
            if (number.find()) {
                rest = rest.substring(number.end());
                continue;
            }
            Matcher letter = LETTER_PREFIX.matcher(rest);
            if (letter.find()) {
                rest = rest.substring(letter.end());
                continue;
            }
            if (rest.startsWith("**") || rest.startsWith("__") || rest.startsWith("`")) {
                rest = rest.startsWith("`") ? rest.substring(1).stripLeading()
                        : rest.substring(2).stripLeading();
                continue;
            }
            break;
        }
        for (String field : REQUIRED_FIELDS) {
            if (!rest.startsWith(field)) {
                continue;
            }
            int cursor = field.length();
            if (cursor < rest.length()
                    && (Character.isLetterOrDigit(rest.charAt(cursor)) || rest.charAt(cursor) == '_')) {
                continue;
            }
            while (cursor < rest.length()
                    && (rest.charAt(cursor) == '*' || rest.charAt(cursor) == '_'
                    || rest.charAt(cursor) == '`' || Character.isWhitespace(rest.charAt(cursor)))) {
                cursor++;
            }
            if (cursor >= rest.length() || rest.charAt(cursor) != '=') {
                continue;
            }
            String value = rest.substring(cursor + 1).trim();
            int leading = line.length() - line.stripLeading().length();
            return new Assignment(field, value, lineStart + leading, lineEnd);
        }
        return null;
    }

    private static boolean isAssignmentContinuation(String currentValue, String nextLine) {
        String next = nextLine == null ? "" : nextLine.trim();
        if (next.isEmpty()) {
            return currentValue == null || currentValue.isBlank() || hasUnclosedBracket(currentValue);
        }
        String value = currentValue == null ? "" : currentValue.trim();
        return next.startsWith("=") || hasUnclosedBracket(value)
                || value.endsWith("=") || value.endsWith("(")
                || value.endsWith("AND") || value.endsWith("OR") || value.endsWith("NOT");
    }

    private static String parseAssignmentSource(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = SOURCE_VALUE.matcher(raw.replace("`", "").trim());
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (!isAllowedAnnotationSuffix(raw.replace("`", "").trim().substring(matcher.end()))) {
            return null;
        }
        return SOURCE_TIERS.contains(value) ? value : null;
    }

    private static Boolean parseAssignmentBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("`", "").trim();
        Matcher matcher = BOOLEAN_TOKEN.matcher(value);
        List<MatcherResult> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(new MatcherResult(matcher.start(), matcher.end(), matcher.group(1)));
        }
        for (int index = tokens.size() - 1; index >= 0; index--) {
            MatcherResult token = tokens.get(index);
            if (isSafeBooleanExpression(value.substring(0, token.end()))
                    && isAllowedAnnotationSuffix(value.substring(token.end()))) {
                return Boolean.valueOf(token.value());
            }
        }
        return null;
    }

    private static boolean isSafeBooleanExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        int cursor = 0;
        int parentheses = 0;
        boolean sawToken = false;
        while (cursor < expression.length()) {
            char current = expression.charAt(cursor);
            if (Character.isWhitespace(current) || current == ',' || current == '.'
                    || current == ':' || current == ';') {
                cursor++;
                continue;
            }
            if (current == '(') {
                parentheses++;
                cursor++;
                continue;
            }
            if (current == ')') {
                if (parentheses == 0) {
                    return false;
                }
                parentheses--;
                cursor++;
                continue;
            }
            if (current == '=') {
                cursor++;
                continue;
            }
            if (current == '!') {
                cursor++;
                continue;
            }
            if (!Character.isLetter(current)) {
                return false;
            }
            int start = cursor++;
            while (cursor < expression.length()
                    && (Character.isLetterOrDigit(expression.charAt(cursor))
                    || expression.charAt(cursor) == '_')) {
                cursor++;
            }
            String token = expression.substring(start, cursor);
            String normalized = token.toLowerCase(Locale.ROOT);
            if (!("true".equals(normalized) || "false".equals(normalized)
                    || "and".equals(normalized) || "or".equals(normalized)
                    || "not".equals(normalized)
                    || EXPRESSION_IDENTIFIERS.stream()
                    .map(item -> item.toLowerCase(Locale.ROOT)).toList().contains(normalized))) {
                return false;
            }
            sawToken = true;
        }
        return sawToken && parentheses == 0;
    }

    private static List<String> parseAssignmentCitationIds(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("`", "").trim();
        int open = value.indexOf('[');
        if (open < 0 || !isSafeBooleanExpression(value.substring(0, open).trim().isEmpty()
                ? "A"
                : value.substring(0, open))) {
            return null;
        }
        int close = findMatchingBracket(value, open);
        if (close < 0) {
            return null;
        }
        String arrayText = value.substring(open, close + 1);
        String suffix = value.substring(close + 1);
        if (!isAllowedAnnotationSuffix(suffix)) {
            return null;
        }

        try {
            JsonNode node = STRICT_JSON.readTree(arrayText);
            if (node != null && node.isArray()) {
                List<String> ids = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode item : node) {
                    if (!item.isTextual() || item.asText().isBlank()
                            || !item.asText().equals(item.asText().trim())
                            || !seen.add(item.asText())) {
                        return null;
                    }
                    ids.add(item.asText());
                }
                return ids;
            }
        } catch (Exception ignored) {
            // Assignment syntax also commonly uses [E1] rather than JSON's
            // ["E1"]. The strict bare-id fallback below is still unambiguous.
        }

        String body = arrayText.substring(1, arrayText.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : body.split(",", -1)) {
            String id = part.trim();
            if (!BARE_ARRAY_ID.matcher(id).matches() || !seen.add(id)) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    private static int findMatchingBracket(String value, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = open; index < value.length(); index++) {
            char current = value.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean hasUnclosedBracket(String value) {
        return findMatchingBracket(value, Math.max(0, value.indexOf('['))) < 0
                && value.indexOf('[') >= 0;
    }

    private static boolean isAllowedAnnotationSuffix(String suffix) {
        String value = suffix == null ? "" : suffix.trim();
        while (!value.isEmpty() && ".,;:✓".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty()) {
            return true;
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length() - 1).trim();
        } else if (!(value.startsWith("since ") || value.startsWith("because ")
                || value.startsWith("when ") || value.startsWith("if "))) {
            return false;
        }
        for (String token : value.split("[^A-Za-z0-9_]+")) {
            if (!token.isBlank() && !ANNOTATION_WORDS.stream()
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .toList().contains(token.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A conservative request signature used before buffering a streamed answer.
     * It keeps ordinary {@code json_object} requests on their normal streaming
     * path while recognizing the explicit AI-news decision schema.
     */
    public static boolean containsDecisionFieldSignature(CharSequence content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String text = content.toString();
        return REQUIRED_FIELDS.stream().allMatch(text::contains);
    }

    private static DecisionValidation validateDecisionObject(JsonNode root) {
        boolean looksLikeDecision = false;
        var names = root.fieldNames();
        while (names.hasNext()) {
            if (REQUIRED_FIELD_SET.contains(names.next())) {
                looksLikeDecision = true;
                break;
            }
        }
        if (!looksLikeDecision) {
            return new DecisionValidation(false, null, "");
        }

        List<String> failures = new ArrayList<>();
        Set<String> actualFields = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        List<String> missing = REQUIRED_FIELDS.stream()
                .filter(field -> !actualFields.contains(field))
                .toList();
        if (!missing.isEmpty()) {
            failures.add("missing required decision fields: " + missing);
        }
        List<String> unexpected = actualFields.stream()
                .filter(field -> !REQUIRED_FIELD_SET.contains(field))
                .toList();
        if (!unexpected.isEmpty()) {
            failures.add("unexpected decision fields: " + unexpected);
        }

        String sourceTier = requiredText(root, "sourceTier", failures);
        if (sourceTier != null && !SOURCE_TIERS.contains(sourceTier)) {
            failures.add("sourceTier must be exactly official, media, or community");
        }
        Boolean verificationEligible = requiredBoolean(root, "verificationEligible", failures);
        Boolean citationAllowed = requiredBoolean(root, "citationAllowed", failures);
        Boolean claimQuoteSupported = requiredBoolean(root, "claimQuoteSupported", failures);
        Boolean refusalIssued = requiredBoolean(root, "refusalIssued", failures);
        Boolean humanReviewRequested = requiredBoolean(root, "humanReviewRequested", failures);
        List<String> citationIds = requiredStringArray(root, "citationIds", failures);

        if (verificationEligible != null && claimQuoteSupported != null
                && verificationEligible && !claimQuoteSupported) {
            failures.add("verificationEligible=true requires claimQuoteSupported=true");
        }
        if (verificationEligible != null && refusalIssued != null
                && refusalIssued == verificationEligible) {
            failures.add("refusalIssued must equal !verificationEligible");
        }
        if (citationAllowed != null && verificationEligible != null
                && citationAllowed && !verificationEligible) {
            failures.add("citationAllowed=true requires verificationEligible=true");
        }
        if (citationAllowed != null && humanReviewRequested != null
                && humanReviewRequested == citationAllowed) {
            failures.add("humanReviewRequested must equal !citationAllowed");
        }
        if (citationAllowed != null && citationIds != null) {
            if (!citationAllowed && !citationIds.isEmpty()) {
                failures.add("citationIds must be [] when citationAllowed=false");
            } else if (citationAllowed && citationIds.isEmpty()) {
                failures.add("citationAllowed=true requires at least one citation id");
            }
        }

        if (!failures.isEmpty()) {
            return new DecisionValidation(true, null, String.join("; ", failures));
        }
        return new DecisionValidation(true, new Decision(sourceTier, verificationEligible,
                citationAllowed, claimQuoteSupported, refusalIssued, humanReviewRequested, citationIds), "");
    }

    private static String requiredText(JsonNode root, String name, List<String> failures) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            failures.add(name + " must be a nonblank JSON string");
            return null;
        }
        return value.asText();
    }

    private static Boolean requiredBoolean(JsonNode root, String name, List<String> failures) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) {
            failures.add(name + " must be a JSON boolean");
            return null;
        }
        return value.booleanValue();
    }

    private static List<String> requiredStringArray(JsonNode root, String name, List<String> failures) {
        JsonNode value = root.get(name);
        if (value == null || !value.isArray()) {
            failures.add(name + " must be a JSON string array");
            return null;
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                failures.add(name + " must contain only nonblank strings");
                continue;
            }
            String id = item.asText();
            if (!id.equals(id.trim())) {
                failures.add(name + " values must not contain surrounding whitespace");
            }
            if (!seen.add(id)) {
                failures.add(name + " must not contain duplicate ids");
            }
            ids.add(id);
        }
        return ids;
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "invalid JSON object";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    public record ParseResult(boolean jsonObject, boolean decisionCandidate, Decision decision,
                              String failureReason) {
        public boolean validDecision() {
            return decision != null;
        }
    }

    public record ContextValidation(boolean valid, String failureReason) {
    }

    public record Decision(String sourceTier, boolean verificationEligible, boolean citationAllowed,
                           boolean claimQuoteSupported, boolean refusalIssued,
                           boolean humanReviewRequested, List<String> citationIds) {
        public Decision {
            citationIds = List.copyOf(citationIds);
        }

        /** Stable field ordering for a reconciled terminal JSON response. */
        public String canonicalJson() {
            ObjectNode root = STRICT_JSON.createObjectNode();
            root.put("sourceTier", sourceTier);
            root.put("verificationEligible", verificationEligible);
            root.put("citationAllowed", citationAllowed);
            root.put("claimQuoteSupported", claimQuoteSupported);
            root.put("refusalIssued", refusalIssued);
            root.put("humanReviewRequested", humanReviewRequested);
            var ids = root.putArray("citationIds");
            citationIds.forEach(ids::add);
            return root.toString();
        }
    }

    private record LocatedDecision(Decision decision, int position) {
    }

    private record Section(int start, int end) {
    }

    private record Assignment(String field, String value, int start, int end) {
    }

    private record MatcherResult(int start, int end, String value) {
    }

    private record DecisionValidation(boolean decisionCandidate, Decision decision, String failureReason) {
    }
}
