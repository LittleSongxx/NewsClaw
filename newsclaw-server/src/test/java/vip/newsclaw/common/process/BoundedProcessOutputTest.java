package vip.newsclaw.common.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class BoundedProcessOutputTest {

    @Test
    void outputOverflowTerminatesProducer() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "yes x").start();
        try (BoundedProcessOutput output = BoundedProcessOutput.start(process, 1024)) {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            output.await();
            assertTrue(output.exceeded());
            assertTrue(output.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
        }
    }
}
