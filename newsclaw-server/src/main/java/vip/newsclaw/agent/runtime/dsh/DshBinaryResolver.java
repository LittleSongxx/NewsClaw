package vip.newsclaw.agent.runtime.dsh;

import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface DshBinaryResolver {
    Optional<Path> resolve();
}
