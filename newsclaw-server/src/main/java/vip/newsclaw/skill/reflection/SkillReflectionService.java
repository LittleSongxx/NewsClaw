package vip.newsclaw.skill.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.newsclaw.agent.AgentGraphBuilder;
import vip.newsclaw.agent.prompt.PromptLoader;
import vip.newsclaw.llm.model.ModelConfigEntity;
import vip.newsclaw.llm.service.ModelConfigService;
import vip.newsclaw.memory.event.ConversationCompletedEvent;
import vip.newsclaw.skill.model.SkillEntity;
import vip.newsclaw.skill.proposal.SkillChangeProposalService;
import vip.newsclaw.skill.proposal.SkillProposalDraft;
import vip.newsclaw.skill.service.SkillService;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.conversation.model.ConversationEntity;
import vip.newsclaw.workspace.conversation.model.MessageEntity;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Out-of-band skill reflection — after a conversation finishes, reviews the
 * recent turns and produces reviewable skill-change proposals, mirroring the
 * memory-nudge cadence without autonomously mutating the production registry.
 *
 * <p>The review runs on an async thread so it never blocks the user response
 * and never consumes the live turn's context window. It only decides
 * <em>what</em> could be useful. A workspace administrator approves the exact
 * diff, and the proposal service then re-enters the normal skill-management
 * pipeline with a restore snapshot.
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillReflectionService {

    private final ConversationService conversationService;
    private final SkillService skillService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final SkillReflectionProperties properties;
    private final ObjectMapper objectMapper;
    private final LockProvider lockProvider;

    /** Proposal-first sink. Setter injection keeps legacy unit construction stable. */
    private SkillChangeProposalService proposalService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProposalService(SkillChangeProposalService proposalService) {
        this.proposalService = proposalService;
    }

    /**
     * Per-conversation review bookkeeping: when the last review ran (cooldown)
     * and the message count it ran at (cadence high-water mark).
     *
     * @param lastRunAt         wall-clock time of the last attempted review
     * @param reviewedAtMessage conversation message count at that attempt
     */
    private record ReviewState(Instant lastRunAt, int reviewedAtMessage) {
    }

    /** Per-conversation cadence + cooldown tracking. */
    private final ConcurrentHashMap<String, ReviewState> reviewStates = new ConcurrentHashMap<>();

    /**
     * Cap on tracked conversations. The map is a cadence accelerator, not a
     * source of truth — dropping the oldest entries only means those
     * conversations get one extra review opportunity, so a coarse eviction is
     * enough to keep a long-lived server from accumulating one entry per
     * conversation forever.
     */
    private static final int MAX_TRACKED_CONVERSATIONS = 2000;
    /** Atomic single-flight claims for concurrent completion events. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    /** Per-message truncation when building the review transcript. */
    private static final int MESSAGE_TRUNCATE_CHARS = 1200;
    /** Per-skill body truncation when building the catalog. */
    private static final int CATALOG_BODY_TRUNCATE_CHARS = 1200;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+[a-z0-9._~+/-]{12,}|(?:api[_-]?key|password|passwd|secret|token)\\s*[:=]\\s*[^\\s,;]{6,}|sk-[a-z0-9_-]{12,})");

    @Async
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        if (event == null) {
            return;
        }
        maybeReflect(event.agentId(), event.conversationId(), event.messageCount());
    }

    /**
     * Decide whether a review should run for this conversation and execute it
     * if the cadence, tool-use floor, and cooldown gates all pass.
     */
    @Async
    public void maybeReflect(Long agentId, String conversationId, int messageCount) {
        if (!properties.isEnabled() || agentId == null || conversationId == null) {
            return;
        }
        int interval = properties.getReviewTurnInterval();
        if (interval <= 0) {
            return;
        }
        // Cadence gate: at least N new messages since the last attempt.
        // Deliberately a high-water mark rather than `messageCount % interval`
        // — the count is the conversation total at publish time and can jump by
        // more than one per event (batched persistence, tool messages, channel
        // replays), so an exact-multiple test silently skips whole review
        // opportunities whenever it steps over the multiple.
        ReviewState state = reviewStates.get(conversationId);
        int reviewedAt = state == null ? 0 : state.reviewedAtMessage();
        if (messageCount - reviewedAt < interval) {
            return;
        }
        if (isInCooldown(state)) {
            log.debug("[SkillReflect] conversation {} in cooldown, skipping", conversationId);
            return;
        }
        if (inFlight.putIfAbsent(conversationId, Boolean.TRUE) != null) {
            log.debug("[SkillReflect] conversation {} already being reviewed, skipping", conversationId);
            return;
        }
        try {
            ReviewState claimedState = reviewStates.get(conversationId);
            int claimedAt = claimedState == null ? 0 : claimedState.reviewedAtMessage();
            if (messageCount - claimedAt < interval || isInCooldown(claimedState)) {
                return;
            }
            Duration distributedCooldown = Duration.ofMinutes(Math.max(0, properties.getCooldownMinutes()));
            java.util.Optional<SimpleLock> distributedLock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), reflectionLockName(conversationId),
                    distributedCooldown.plusMinutes(10), distributedCooldown));
            if (distributedLock.isEmpty()) {
                log.debug("[SkillReflect] conversation {} held by another node", conversationId);
                return;
            }
            try {
                evictIfOversized();
                reviewStates.put(conversationId, new ReviewState(Instant.now(), messageCount));
                if (!doReflect(agentId, conversationId)) {
                    log.debug("[SkillReflect] conv {} yielded no review this cycle", conversationId);
                }
            } finally {
                try {
                    distributedLock.get().unlock();
                } catch (Exception e) {
                    log.warn("[SkillReflect] distributed lock release failed for {}: {}",
                            conversationId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SkillReflect] Failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage());
        } finally {
            inFlight.remove(conversationId);
        }
    }

    /** @return {@code true} when a review actually ran (cooldown should advance). */
    private boolean doReflect(Long agentId, String conversationId) {
        // 1. Derive tenant identity from the persisted conversation. Event
        // payloads are notifications, not an authorization source.
        ConversationEntity conversation = conversationService.findByConversationId(conversationId);
        if (conversation == null || conversation.getWorkspaceId() == null
                || conversation.getWorkspaceId() <= 0
                || conversation.getAgentId() == null
                || !conversation.getAgentId().equals(agentId)) {
            log.warn("[SkillReflect] Rejecting unscoped/mismatched conversation: agent={}, conv={}",
                    agentId, conversationId);
            return false;
        }
        Long workspaceId = conversation.getWorkspaceId();

        // 2. Load the recent window of the conversation.
        List<MessageEntity> messages = conversationService.listMessages(conversationId);
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int maxReview = properties.getMaxMessages();
        List<MessageEntity> recent = messages.size() > maxReview
                ? messages.subList(messages.size() - maxReview, messages.size())
                : messages;

        // 2. Substance floor — a window with too few assistant turns rarely
        // yields a reusable skill. Tool calls are not persisted as separate
        // messages, so assistant-turn count is the observable signal.
        long assistantTurns = recent.stream().filter(m -> "assistant".equals(m.getRole())).count();
        if (assistantTurns < properties.getMinAssistantTurns()) {
            log.debug("[SkillReflect] conv {} below assistant-turn floor ({} < {}), skipping",
                    conversationId, assistantTurns, properties.getMinAssistantTurns());
            return false;
        }

        String transcript = buildTranscript(recent);
        if (transcript.isBlank()) {
            return false;
        }
        String skillCatalog = buildSkillCatalog(workspaceId, properties.getCatalogCharBudget());

        // 3. Ask the reviewer for a JSON action plan.
        String llmResponse;
        try {
            String systemPrompt = PromptLoader.loadPrompt("skill/reflect-system");
            String userPrompt = PromptLoader.loadPrompt("skill/reflect-user")
                    .replace("{skills}", skillCatalog.isBlank() ? "(no skills yet)" : skillCatalog)
                    .replace("{transcript}", transcript);
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            llmResponse = callLlmWithRetry(chatModel, prompt, 2);
        } catch (Exception e) {
            log.warn("[SkillReflect] LLM call failed for conv={}: {}", conversationId, e.getMessage());
            return true;
        }
        if (llmResponse == null) {
            return true;
        }

        // 4. Parse the plan and persist reviewable proposals. Reflection never
        // writes a live Skill directly: production promotion is an explicit
        // workspace-admin operation with a snapshot and a second security scan.
        JsonNode plan = parseJsonResponse(llmResponse);
        if (plan == null || !plan.isArray() || plan.isEmpty()) {
            log.debug("[SkillReflect] No actions proposed for conv={}", conversationId);
            return true;
        }

        if (proposalService == null) {
            log.warn("[SkillReflect] Proposal service is unavailable; refusing autonomous Skill mutation for conv={}",
                    conversationId);
            return true;
        }

        int proposed = 0;
        for (JsonNode action : plan) {
            if (proposed >= properties.getMaxActionsPerRun()) {
                log.info("[SkillReflect] Hit maxActionsPerRun={} for conv={}, stopping",
                        properties.getMaxActionsPerRun(), conversationId);
                break;
            }
            if (proposeAction(action, workspaceId, agentId, conversationId)) {
                proposed++;
            }
        }
        if (proposed > 0) {
            log.info("[SkillReflect] Created {} reviewable Skill proposal(s) from conv={}", proposed, conversationId);
        }
        return true;
    }

    /** Convert one reviewer action into a proposal; no live Skill mutation occurs here. */
    private boolean proposeAction(JsonNode action, Long workspaceId, Long agentId, String conversationId) {
        String act = action.path("action").asText("").strip().toLowerCase();
        String name = action.path("name").asText("").strip();
        if (act.isBlank() || name.isBlank()) {
            return false;
        }
        // Reflection never deletes — it only creates or improves.
        // Full replacement from a truncated/untrusted catalog is unsafe: the
        // reviewer cannot preserve content it did not receive. Restrict the
        // autonomous path to additive create and exact-context patch.
        if (!List.of("create", "patch").contains(act)) {
            log.debug("[SkillReflect] Ignoring unsupported action '{}'", act);
            return false;
        }
        String content = action.path("content").asText(null);
        String oldText = action.path("oldText").asText(null);
        String newText = action.path("newText").asText(null);
        String proposed = "create".equals(act) ? content : newText;
        if (proposed == null) {
            return false;
        }
        try {
            proposalService.propose(new SkillProposalDraft(workspaceId, agentId, "REFLECTION", conversationId,
                    null, act, name, content, oldText, newText,
                    action.path("reason").asText("")));
            log.info("[SkillReflect] Proposed {} '{}' — {}", act, name,
                    action.path("reason").asText(""));
            return true;
        } catch (Exception e) {
            log.warn("[SkillReflect] Proposal {} '{}' failed: {}", act, name, e.getMessage());
            return false;
        }
    }

    /** Existing non-builtin skills with truncated bodies, capped to a char budget. */
    private String buildSkillCatalog(Long workspaceId, int charBudget) {
        List<SkillEntity> skills = skillService.listEnabledSkills(workspaceId);
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SkillEntity skill : skills) {
            if (Boolean.TRUE.equals(skill.getBuiltin())) {
                continue;
            }
            String entry = "### " + skill.getName() + "\n"
                    + (skill.getDescription() == null ? "" : skill.getDescription().strip() + "\n")
                    + redactSensitive(truncate(skill.getSkillContent(), CATALOG_BODY_TRUNCATE_CHARS)) + "\n\n";
            if (sb.length() + entry.length() > charBudget) {
                sb.append("... (catalog truncated)\n");
                break;
            }
            sb.append(entry);
        }
        return sb.toString().strip();
    }

    private String buildTranscript(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String label = switch (role == null ? "" : role) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                case "tool" -> "Tool[" + (msg.getToolName() != null ? msg.getToolName() : "unknown") + "]";
                default -> null;
            };
            if (label == null) {
                continue;
            }
            sb.append(label).append(": ")
                    .append(redactSensitive(truncate(content, MESSAGE_TRUNCATE_CHARS)))
                    .append("\n\n");
        }
        return sb.toString().strip();
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = null;
        if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
            try {
                model = modelConfigService.getModel(Long.parseLong(properties.getModelId()));
            } catch (Exception e) {
                log.warn("[SkillReflect] Invalid modelId '{}', falling back to default", properties.getModelId());
            }
        }
        if (model == null) {
            model = modelConfigService.getDefaultModel();
        }
        return agentGraphBuilder.buildRuntimeChatModel(model);
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String cleaned = response.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.strip();
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("[SkillReflect] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String callLlmWithRetry(ChatModel chatModel, Prompt prompt, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ChatResponse response = chatModel.call(prompt);
                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    return response.getResult().getOutput().getText();
                }
                return null;
            } catch (Exception e) {
                if (attempt < maxRetries && isRateLimitError(e)) {
                    long delay = 5000L * (attempt + 1);
                    log.info("[SkillReflect] Rate limited, waiting {}ms before retry ({}/{})",
                            delay, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            }
        }
        return null;
    }

    private boolean isRateLimitError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("rate_limit")
                || msg.contains("Too Many Requests"));
    }

    private boolean isInCooldown(ReviewState state) {
        if (state == null || state.lastRunAt() == null) {
            return false;
        }
        long cooldownSeconds = properties.getCooldownMinutes() * 60L;
        return Instant.now().isBefore(state.lastRunAt().plusSeconds(cooldownSeconds));
    }

    /**
     * Drop the least-recently-reviewed entries once the tracking map grows past
     * {@link #MAX_TRACKED_CONVERSATIONS}, so a long-running server does not
     * retain one entry per conversation for its whole uptime.
     */
    private void evictIfOversized() {
        if (reviewStates.size() < MAX_TRACKED_CONVERSATIONS) {
            return;
        }
        reviewStates.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().lastRunAt()))
                .limit(Math.max(1, MAX_TRACKED_CONVERSATIONS / 4))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(reviewStates::remove);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
    }

    private static String redactSensitive(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        return SECRET_PATTERN.matcher(text).replaceAll("[REDACTED_SECRET]");
    }

    private static String reflectionLockName(String conversationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return "skill-reflect-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
