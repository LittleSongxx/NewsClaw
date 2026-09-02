package vip.newsclaw.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.newsclaw.NewsClawApplication;
import vip.newsclaw.channel.event.ChannelMessageReceivedEvent;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.service.TriggerService;
import vip.newsclaw.workflow.model.WorkflowRunEntity;
import vip.newsclaw.workflow.repository.WorkflowRunMapper;
import vip.newsclaw.workflow.runtime.StubAgentInvoker;
import vip.newsclaw.workflow.runtime.StubAgentInvokerConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms channel_message + content_match are wired as real event
 * sources: when the channel module publishes a
 * {@link ChannelMessageReceivedEvent}, the trigger bridge forwards it
 * into the ingest pipeline and a matching trigger fires its target
 * workflow.
 *
 * <p>The test publishes the event directly via
 * {@link ApplicationEventPublisher} rather than building a full channel
 * adapter — that's the contract the channel router commits to, and
 * skipping the adapter keeps the test focused on the bridge wiring.
 */
@SpringBootTest(
        classes = NewsClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:channel_trigger_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "newsclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class, TriggerDispatcherWorkflowTest.StubGraphLoaderConfig.class})
class ChannelMessageTriggerTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TriggerService triggerService;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private TriggerDispatcherWorkflowTest.StubGraphLoader stubGraphLoader;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("channel_message trigger fires its target workflow on a matching channelType.")
    void channelMessageRoutesToWorkflow() {
        long workspace = 7700L;
        long downstream = 7710L;

        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");
        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("on-feishu");
        t.setPatternType("channel_message");
        // narrow to a specific channelType — the matcher reads channelType
        // out of envelope.data, which the bridge populates from the event.
        t.setPatternJson("{\"channelType\":\"feishu\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new ChannelMessageReceivedEvent(
                workspace, "feishu", "msg-1", "alice", "Alice", "chat-1", "hello",
                "feishu:channel-1:chat-1", 42L));

        List<WorkflowRunEntity> runs = awaitRuns(downstream);
        assertEquals(1, runs.size(), "channel_message envelope should have triggered exactly one run");
        assertEquals("succeeded", runs.get(0).getState());
        assertTrue(runs.get(0).getTriggeredBy() != null
                        && runs.get(0).getTriggeredBy().startsWith("trigger:"),
                "downstream run should be triggered_by trigger:* — got "
                        + runs.get(0).getTriggeredBy());
        assertTrue(runs.get(0).getTriggeredMeta() != null
                        && runs.get(0).getTriggeredMeta().contains("\"conversationId\":\"feishu:channel-1:chat-1\"")
                        && runs.get(0).getTriggeredMeta().contains("\"cronOrigin\":false"),
                "channel origin must survive into the durable run metadata: "
                        + runs.get(0).getTriggeredMeta());
    }

    @Test
    @DisplayName("channel_message trigger keyed on a different channelType stays dormant.")
    void wrongChannelTypeDoesNotMisfire() {
        long workspace = 7800L;
        long downstream = 7810L;

        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");
        stubInvoker.respond("greeter", "ok");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("only-dingtalk");
        t.setPatternType("channel_message");
        t.setPatternJson("{\"channelType\":\"dingtalk\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new ChannelMessageReceivedEvent(
                workspace, "feishu", "msg-2", "bob", "Bob", "chat-2", "hello"));

        List<WorkflowRunEntity> runs = awaitNoRuns(downstream);
        assertTrue(runs.isEmpty(),
                "channelType mismatch should leave the trigger dormant");
    }

    @Test
    @DisplayName("content_match trigger fires when the message body contains the configured substring.")
    void contentMatchRoutesToWorkflow() {
        long workspace = 7900L;
        long downstream = 7910L;

        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");
        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"chained\"}]}");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("on-order-keyword");
        t.setPatternType("content_match");
        t.setPatternJson("{\"substring\":\"order\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new ChannelMessageReceivedEvent(
                workspace, "feishu", "msg-3", "alice", "Alice", "chat-3", "Place an Order, please"));

        List<WorkflowRunEntity> runs = awaitRuns(downstream);
        assertEquals(1, runs.size(),
                "content_match should fire when the substring is present in the message");
    }

    @Test
    @DisplayName("content_match trigger does NOT fire when the substring is missing.")
    void contentMatchSkipsWhenSubstringAbsent() {
        long workspace = 8000L;
        long downstream = 8010L;

        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("only-order");
        t.setPatternType("content_match");
        t.setPatternJson("{\"substring\":\"order\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new ChannelMessageReceivedEvent(
                workspace, "feishu", "msg-4", "alice", "Alice", "chat-4", "completely unrelated text"));

        List<WorkflowRunEntity> runs = awaitNoRuns(downstream);
        assertTrue(runs.isEmpty(),
                "missing substring should leave the content_match trigger dormant");
    }

    // The channel-message event bridge dispatches on an @Async listener, so the
    // downstream run is produced off the event-publishing thread. Poll briefly
    // for it instead of reading immediately (which would race the listener).
    private List<WorkflowRunEntity> awaitRuns(long workflowId) {
        var q = new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, workflowId);
        for (int i = 0; i < 50; i++) {
            List<WorkflowRunEntity> runs = runMapper.selectList(q);
            // The async trigger persists the run before the worker advances it
            // from running to a terminal state. Wait for that transition too,
            // otherwise a fast assertion can race a perfectly healthy run.
            if (!runs.isEmpty() && runs.stream().allMatch(run -> isTerminal(run.getState()))) return runs;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return runMapper.selectList(q);
    }

    private boolean isTerminal(String state) {
        if (state == null) return false;
        return "succeeded".equalsIgnoreCase(state)
                || "failed".equalsIgnoreCase(state)
                || "cancelled".equalsIgnoreCase(state)
                || "timed_out".equalsIgnoreCase(state);
    }

    // Give the @Async listener time to run, then confirm it produced nothing.
    private List<WorkflowRunEntity> awaitNoRuns(long workflowId) {
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, workflowId));
    }
}
