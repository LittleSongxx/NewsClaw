package vip.newsclaw.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.trigger.dispatch.TriggerDispatcher;
import vip.newsclaw.trigger.dispatch.WorkflowGraphLoader;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.workflow.compiler.PebbleSubsetEvaluator;
import vip.newsclaw.workflow.runtime.WorkflowRunner;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TriggerDispatcherDailyWindowTest {

    @Test
    @SuppressWarnings("unchecked")
    void dailyWindowRetainsNullableTemplateInputs() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(mock(WorkflowGraphLoader.class),
                mock(WorkflowRunner.class), mock(PebbleSubsetEvaluator.class), new ObjectMapper());
        TriggerEntity trigger = new TriggerEntity();
        trigger.setName("ai-news.template.v1.daily-radar");
        trigger.setPatternType("cron");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("optional", null);

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                dispatcher, "withDailyWindow", trigger, inputs);

        assertTrue(result.containsKey("windowStart"));
        assertTrue(result.containsKey("windowEnd"));
        assertTrue(result.containsKey("optional"));
        assertNull(result.get("optional"));
    }
}
