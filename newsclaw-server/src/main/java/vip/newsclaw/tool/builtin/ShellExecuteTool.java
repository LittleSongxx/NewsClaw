package vip.newsclaw.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.newsclaw.tool.document.GeneratedFileCache;
import vip.newsclaw.tool.document.WorkspaceArtifactSurfacer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import vip.newsclaw.common.process.BoundedProcessOutput;
import vip.newsclaw.common.process.ProcessTreeTerminator;

/**
 * 内置工具：本地命令执行（跨平台）
 * <p>
 * 安全边界说明：
 * <ul>
 *   <li>所有调用在执行前必须经过 ToolGuard 审批（DefaultToolGuard 对 shell 工具默认返回 NEEDS_APPROVAL）</li>
 *   <li>超时控制：默认 60 秒，超时后强制终止进程</li>
 *   <li>输出长度限制：stdout/stderr 各最多 10000 字节，防止大输出撑爆内存</li>
 *   <li>平台适配：Windows 使用 cmd.exe /D /S /C，Linux/macOS 使用 /bin/sh -c。
 *       风险已通过 ToolGuard 审批机制控制——每次调用都需要用户明确批准。</li>
 *   <li>输出重定向到临时文件而非管道，确保 timeout 不被管道阻塞失效。
 *       参考 NewsClaw _execute_subprocess_sync 和 claude-code-haha file-mode 思路。</li>
 * </ul>
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class ShellExecuteTool {

    private final vip.newsclaw.i18n.I18nService i18n;
    private final GeneratedFileCache generatedFileCache;

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 10_000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    @vip.newsclaw.tool.ConcurrencyUnsafe("shell command execution can mutate global state in ways the executor can't reason about")
    @Tool(description = "Execute a shell command on the local server. For running system commands, viewing files, running scripts. "
            + "Uses cmd.exe on Windows, /bin/sh on Linux/macOS. "
            + "Dangerous operations trigger security approval. Returns structured result with exitCode, stdout, stderr, timedOut, "
            + "and (when the command wrote files) a generatedFiles string of [name](url) download links — echo them so the user can download what you produced.")
    public String execute_shell_command(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(description = "Timeout in seconds, default 60", required = false) Integer timeoutSeconds,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator. Carries the
            // ChatOrigin so the workspace boundary check honors per-agent basePath.
            @Nullable ToolContext ctx) {

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        // 硬上限：不允许超过 300 秒
        timeout = Math.min(timeout, 300);

        log.info("[ShellExecute] Executing command (os={}): {}, timeout={}s",
                IS_WINDOWS ? "windows" : "unix", truncateForLog(command), timeout);

        JSONObject result = new JSONObject();
        result.set("command", command);

        // Enforce the workspace boundary on the command string itself before
        // the process starts. The pb.directory() set later only constrains
        // the CWD — absolute paths in the command would still reach anywhere.
        try {
            vip.newsclaw.tool.guard.WorkspacePathGuard.validateShellCommand(command, ctx);
        } catch (IllegalArgumentException e) {
            log.warn("[ShellExecute] Sandbox rejected command: {}", e.getMessage());
            result.set("exitCode", -1);
            result.set("stdout", "");
            result.set("stderr", e.getMessage());
            result.set("timedOut", false);
            result.set("error", e.getMessage());
            return JSONUtil.toJsonPrettyStr(result);
        }

        Process process = null;
        BoundedProcessOutput output = null;

        try {
            // 处理命令中的嵌入换行符（LLM 生成的 JSON 解码后可能包含真实换行）
            // Windows cmd.exe 会在第一个换行处截断命令，Unix sh 也可能误解
            String sanitizedCommand = collapseEmbeddedNewlines(command);

            ProcessBuilder pb = buildShellProcess(sanitizedCommand, ctx);
            // 不继承环境变量中的敏感信息
            pb.environment().keySet().removeIf(key ->
                    key.contains("KEY") || key.contains("SECRET") || key.contains("TOKEN")
                            || key.contains("PASSWORD") || key.contains("CREDENTIAL"));

            long runStart = System.currentTimeMillis();
            process = pb.start();
            output = BoundedProcessOutput.start(process, MAX_OUTPUT_BYTES);

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!completed && !output.exceeded()) ProcessTreeTerminator.kill(process);
            output.await();

            if (output.exceeded()) {
                result.set("exitCode", -1);
                result.set("stdout", output.stdout());
                result.set("stderr", output.stderr());
                result.set("timedOut", false);
                result.set("outputLimitExceeded", true);
                result.set("message", "Process output exceeded " + MAX_OUTPUT_BYTES + " bytes");
            } else if (!completed) {
                // 超时：强制终止进程（树）
                log.warn("[ShellExecute] Command timed out after {}s: {}", timeout, truncateForLog(command));
                result.set("exitCode", -1);
                result.set("stdout", output.stdout());
                result.set("stderr", output.stderr());
                result.set("timedOut", true);
                result.set("message", i18n.msg("tool.shell.error.timeout", timeout));
            } else {
                int exitCode = process.exitValue();
                String stdout = output.stdout();
                String stderr = output.stderr();
                log.info("[ShellExecute] Command completed: exitCode={}, stdout={}chars, stderr={}chars",
                        exitCode, stdout.length(), stderr.length());
                result.set("exitCode", exitCode);
                result.set("stdout", stdout);
                result.set("stderr", stderr);
                result.set("timedOut", false);
                // Surface files the command wrote as one-click downloads (same path
                // as execute_code). A single newline-joined string, not a JSON array,
                // so the link-extraction regex captures the clean filename.
                java.nio.file.Path workingDir = vip.newsclaw.tool.guard.WorkspacePathGuard.getWorkingDirectory(ctx);
                List<String> fileLinks = WorkspaceArtifactSurfacer.collect(generatedFileCache, workingDir, runStart, ctx);
                if (!fileLinks.isEmpty()) {
                    result.set("generatedFiles", String.join("\n", fileLinks));
                }
            }

        } catch (InterruptedException e) {
            // A conversation Stop interrupts the active tool thread. Kill the
            // subprocess tree before returning control; otherwise the Flux is
            // gone but the shell command keeps running in the background.
            if (process != null && process.isAlive()) {
                ProcessTreeTerminator.kill(process);
            }
            Thread.currentThread().interrupt();
            log.info("[ShellExecute] Command interrupted by cancellation");
            result.set("exitCode", -1);
            result.set("stdout", "");
            result.set("stderr", "Command cancelled by user");
            result.set("timedOut", false);
            result.set("cancelled", true);
        } catch (Exception e) {
            log.error("[ShellExecute] Command execution failed: {}", e.getMessage(), e);
            result.set("exitCode", -1);
            result.set("stdout", "");
            result.set("stderr", i18n.msg("tool.shell.error.exception", e.getMessage()));
            result.set("timedOut", false);
            result.set("error", e.getMessage());
        } finally {
            if (output != null) output.close();
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * 根据当前操作系统构建 shell 进程。
     * Windows: cmd.exe /D /S /C "command"
     *   /D 禁用 AutoRun 注册表项，避免副作用
     *   /S 保留引号原样传递给命令
     * Unix: $SHELL -c command (honors the user's interactive shell)
     *   honors the user's interactive shell so alias resolution / PATH
     *   from the calling environment still apply; falls back to /bin/sh
     *   when $SHELL is unset or points at a non-executable path.
     */
    private static ProcessBuilder buildShellProcess(String command, @Nullable ToolContext ctx) {
        ProcessBuilder pb;
        if (IS_WINDOWS) {
            String winCommand = sanitizeWindowsCommand(command);
            pb = new ProcessBuilder("cmd.exe", "/D", "/S", "/C", winCommand);
        } else {
            String shell = selectPosixShell(System.getenv("SHELL"));
            pb = new ProcessBuilder(shell, "-c", command);
        }

        // Pin the process cwd to the same workspace basePath the validator
        // checked against. Using getWorkingDirectory(ctx) (not the no-arg
        // ThreadLocal-only overload) keeps validation and execution on a
        // single source of truth — otherwise a caller that only sets
        // ToolContext could validate against one basePath and run with the
        // ThreadLocal fallback's basePath.
        java.nio.file.Path workingDir = vip.newsclaw.tool.guard.WorkspacePathGuard.getWorkingDirectory(ctx);
        if (workingDir != null && java.nio.file.Files.isDirectory(workingDir)) {
            pb.directory(workingDir.toFile());
            log.info("[ShellExecute] Working directory set to: {}", workingDir);
        }

        return pb;
    }

    /**
     * Collapse embedded newlines for Windows cmd.exe (where they break parsing),
     * but **leave them alone on Unix**.
     * <p>
     * The original implementation collapsed on every platform under the worry
     * that a stray newline could be misread as a command separator on POSIX
     * shells. In practice that worry is wrong for two common idioms the LLM
     * actually uses to write files: heredocs (`cat &lt;&lt;EOF\nbody\nEOF`) and
     * `python &lt;&lt;EOF` invocations. Both depend on real line breaks to
     * delimit the body from the closing tag — collapsing newlines turns
     * `cat &lt;&lt;EOF\nbody\nEOF` into `cat &lt;&lt;EOF body EOF`, which the
     * shell reads as "open heredoc, immediately close, write 0 bytes." The
     * symptom: every chapter file produced by the agent ends up 0-byte.
     * <p>
     * Unix shell already separates commands with `;` or `&amp;&amp;`, not
     * unquoted newlines, so leaving newlines in is actually safer — and
     * heredocs / multi-line commands now behave as the LLM expects. Windows
     * cmd.exe still gets the collapse because there it really does break.
     */
    private static String collapseEmbeddedNewlines(String command) {
        if (command == null || !command.contains("\n")) {
            return command;
        }
        if (!IS_WINDOWS) {
            // POSIX shell handles newlines correctly within heredocs / scripts
            return command;
        }
        return command.replace("\r\n", " ").replace("\n", " ");
    }

    /**
     * Pick the POSIX shell binary to invoke for a non-Windows tool call.
     *
     * <p>Returns {@code userShellEnv} verbatim when:
     * <ul>
     *   <li>the value is non-blank,</li>
     *   <li>parses as a valid path,</li>
     *   <li>and the resolved binary is executable.</li>
     * </ul>
     *
     * <p>Otherwise falls back to {@code /bin/sh} — the legacy hardcoded
     * default. Important: {@code /bin/sh} on Debian/Ubuntu is dash, which
     * does NOT honor users' bash-isms; the whole point of this method is
     * to prefer the user's actual interactive shell when one is configured.
     */
    static String selectPosixShell(String userShellEnv) {
        return selectPosixShell(userShellEnv, Files::isExecutable);
    }

    /**
     * Test seam — same logic as {@link #selectPosixShell(String)} but with
     * an injectable executable check so unit tests can drive every branch
     * without depending on which shells actually exist on the test runner
     * (Windows CI has no {@code /bin/sh}, POSIX dev hosts have varying
     * shells installed). Production callers go through the single-arg
     * overload above.
     */
    static String selectPosixShell(String userShellEnv, Predicate<Path> executableCheck) {
        if (userShellEnv == null || userShellEnv.isBlank()) {
            return "/bin/sh";
        }
        try {
            Path candidate = Path.of(userShellEnv);
            if (executableCheck.test(candidate)) {
                return userShellEnv;
            }
        } catch (InvalidPathException ignored) {
            // Some exotic $SHELL value that isn't a path — fall through to default.
        }
        return "/bin/sh";
    }

    /**
     * 修复 LLM 常见的 Windows 命令转义问题。
     * LLM 有时会产生 bash 风格的反斜杠转义引号 (\"），
     * 如果命令中所有双引号都被反斜杠转义，则认为是 JSON/bash 伪影并去除反斜杠。
     */
    private static String sanitizeWindowsCommand(String command) {
        if (command.contains("\\\"") && !command.replace("\\\"", "").contains("\"")) {
            return command.replace("\\\"", "\"");
        }
        return command;
    }

    /**
     * 尽力终止进程树。
     * Windows: 使用 taskkill /F /T 终止整个进程树（包括子进程）。
     * Unix: destroyForcibly() 发送 SIGKILL，对于 /bin/sh 启动的子进程基本够用。
     * 注意：Windows 上如果 taskkill 失败，仍回退到 destroyForcibly()，
     * 极端情况下可能有子进程残留（如后台 detached 进程）。
     */
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
