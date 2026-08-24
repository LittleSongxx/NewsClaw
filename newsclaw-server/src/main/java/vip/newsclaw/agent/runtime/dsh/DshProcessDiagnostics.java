package vip.newsclaw.agent.runtime.dsh;

import java.nio.file.Path;

public record DshProcessDiagnostics(
        String sessionId,
        Path binary,
        Path sessionHome,
        boolean alive,
        boolean bridgeTokenRedacted
) {}
