package vip.newsclaw.common.process;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Cross-platform best-effort termination of a process and every live descendant. */
public final class ProcessTreeTerminator {

    private ProcessTreeTerminator() {}

    public static void kill(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle child : descendants.reversed()) {
            try { child.destroyForcibly(); } catch (Exception ignored) { }
        }
        try { process.destroyForcibly(); } catch (Exception ignored) { }
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                try { child.destroyForcibly(); } catch (Exception ignored) { }
            }
        }
    }
}
