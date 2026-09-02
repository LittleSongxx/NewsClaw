package vip.newsclaw.common.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Continuously drains stdout/stderr and kills the process if either exceeds its cap. */
public final class BoundedProcessOutput implements AutoCloseable {

    private final Process process;
    private final int maxBytes;
    private final Capture stdout = new Capture();
    private final Capture stderr = new Capture();
    private final AtomicBoolean exceeded = new AtomicBoolean();
    private final Thread stdoutThread;
    private final Thread stderrThread;

    private BoundedProcessOutput(Process process, int maxBytes) {
        this.process = process;
        this.maxBytes = Math.max(1, maxBytes);
        this.stdoutThread = Thread.ofVirtual().name("bounded-process-stdout").start(
                () -> pump(process.getInputStream(), stdout));
        this.stderrThread = Thread.ofVirtual().name("bounded-process-stderr").start(
                () -> pump(process.getErrorStream(), stderr));
    }

    public static BoundedProcessOutput start(Process process, int maxBytes) {
        return new BoundedProcessOutput(process, maxBytes);
    }

    private void pump(InputStream input, Capture capture) {
        byte[] buffer = new byte[4096];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = maxBytes - capture.size();
                if (remaining > 0) capture.write(buffer, 0, Math.min(remaining, read));
                if (read > remaining) {
                    if (exceeded.compareAndSet(false, true)) ProcessTreeTerminator.kill(process);
                    return;
                }
            }
        } catch (IOException ignored) {
            // Process termination closes streams; captured bytes remain available.
        }
    }

    public boolean exceeded() { return exceeded.get(); }
    public String stdout() { return stdout.text(); }
    public String stderr() { return stderr.text(); }

    public void await() {
        join(stdoutThread);
        join(stderrThread);
    }

    private static void join(Thread thread) {
        try { thread.join(2_000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        await();
    }

    private static final class Capture {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        synchronized int size() { return bytes.size(); }
        synchronized void write(byte[] data, int offset, int length) {
            bytes.write(data, offset, length);
        }
        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
