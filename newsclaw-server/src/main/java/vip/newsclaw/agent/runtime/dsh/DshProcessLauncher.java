package vip.newsclaw.agent.runtime.dsh;

import vip.newsclaw.agent.runtime.contract.RuntimeSession;

import java.nio.file.Path;

@FunctionalInterface
public interface DshProcessLauncher {
    DshProcessHandle launch(Path binary, RuntimeSession session, Path sessionHome, String bridgeToken);
}
