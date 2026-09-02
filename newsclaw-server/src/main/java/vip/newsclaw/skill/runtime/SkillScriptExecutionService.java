package vip.newsclaw.skill.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import vip.newsclaw.common.process.BoundedProcessOutput;
import vip.newsclaw.common.process.ProcessTreeTerminator;

/**
 * 技能脚本执行服务
 * 安全执行 scripts/ 目录下的脚本
 * <p>
 * 输出重定向到临时文件，确保 timeout 不被管道阻塞失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillScriptExecutionService {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_OUTPUT_BYTES = 50_000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** Host variables safe and necessary for locating interpreters and temp files. */
    private static final Set<String> SAFE_INHERITED_ENV = Set.of(
            "PATH", "Path", "PATHEXT", "SYSTEMROOT", "SystemRoot", "COMSPEC", "ComSpec",
            "HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP",
            "LANG", "LANGUAGE", "LC_ALL", "TZ");

    private static final Set<String> PROXY_ENV = Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");

    /**
     * Pip mirror config for desktop (non-Docker) deployments. In Docker these
     * arrive as PIP_INDEX_URL / PIP_TRUSTED_HOST env vars (set in
     * docker-compose) and are inherited by ProcessBuilder directly. On the
     * desktop app Java runs on the host — the host may not have those env
     * vars set, so we fall back to Spring config and inject them explicitly.
     */
    @Value("${newsclaw.pip.index-url:}")
    private String pipIndexUrl;

    @Value("${newsclaw.pip.trusted-host:}")
    private String pipTrustedHost;

    /** Supported inline-code languages mapped to the temp-file extension. */
    private static final Map<String, String> LANGUAGE_EXTENSIONS = Map.of(
            "python", ".py",
            "py", ".py",
            "bash", ".sh",
            "sh", ".sh",
            "shell", ".sh",
            "node", ".js",
            "javascript", ".js",
            "js", ".js");

    /**
     * 执行脚本（兼容签名 — 不注入额外 env vars）
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args) {
        return execute(scriptPath, args, Collections.emptyMap());
    }

    /**
     * 执行脚本，附加 env vars 到子进程环境（RFC-091 settings bridge）。
     * 用于把 skill 在 mate_skill_secret 里存的解密 secret 注入到脚本运行
     * 时环境，让 SKILL.md 里 {@code $AIRTABLE_API_KEY} 等引用自然解析。
     *
     * <p>{@code envVars} 中的键值会 OVERRIDE 父进程同名环境变量；空值跳过。
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @param envVars 要注入子进程环境的额外键值对，可为空
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args, Map<String, String> envVars) {
        return executeResolved(scriptPath, args, envVars, DEFAULT_TIMEOUT_SECONDS, true);
    }

    /**
     * Execute LLM-generated source code inline, without a pre-existing script file.
     * <p>
     * Materializes {@code code} into a temporary file (extension chosen from
     * {@code language}) inside {@code workingDir}, runs it through the same
     * interpreter-selection + timeout + output-capping + env-injection path as
     * {@link #execute(Path, List, Map)}, then deletes the temp file.
     *
     * <p>This is what makes a documentation-only skill (a SKILL.md with no
     * {@code scripts:} entries) runnable: the agent reads the instructions,
     * generates code, and runs it here.
     *
     * @param language       one of python / bash / node (and aliases); selects the interpreter
     * @param code           the source code to run; must be non-blank
     * @param workingDir     directory the temp file is written to and the process cwd. When {@code null}
     *                       a private temp scratch directory is created and removed afterward; when
     *                       non-null it must be an existing directory (e.g. a skill or workspace dir)
     * @param args           optional positional arguments passed to the program
     * @param envVars        optional env vars injected into the subprocess (e.g. decrypted skill secrets)
     * @param timeoutSeconds optional timeout override; clamped to (0, {@value #MAX_TIMEOUT_SECONDS}], defaults to {@value #DEFAULT_TIMEOUT_SECONDS}
     * @return execution result
     */
    public ScriptResult executeCode(String language, String code, Path workingDir,
                                    List<String> args, Map<String, String> envVars, Long timeoutSeconds) {
        if (code == null || code.isBlank()) {
            return ScriptResult.error(-1, "No code supplied");
        }
        String ext = language == null ? null
                : LANGUAGE_EXTENSIONS.get(language.trim().toLowerCase(Locale.ROOT));
        if (ext == null) {
            return ScriptResult.error(-1, "Unsupported language: " + language
                    + ". Supported: python, bash, node");
        }
        if (workingDir != null && !Files.isDirectory(workingDir)) {
            return ScriptResult.error(-1, "Working directory does not exist: " + workingDir);
        }

        long timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            timeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        }

        // No caller-supplied directory (e.g. an agent with no workspace base path):
        // run in a private scratch directory and remove it afterward. Mirrors the
        // shell tool tolerating a null working directory rather than failing.
        Path scratchDir = null;
        Path codeFile = null;
        try {
            Path dir = workingDir;
            if (dir == null) {
                scratchDir = Files.createTempDirectory("mc_code_ws_");
                dir = scratchDir;
            }
            // Write the code into the working dir so the process cwd matches the
            // file location — relative paths in the generated code resolve as the
            // author expects, and skill-scoped runs stay inside the skill dir.
            codeFile = Files.createTempFile(dir, "mc_code_", ext);
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);
            if (!IS_WINDOWS && ext.equals(".sh")) {
                codeFile.toFile().setExecutable(true);
            }
            // Scrub sensitive host env vars: the code is LLM-authored, so it must
            // not inherit the server's API keys / tokens. Skill secrets, when
            // supplied via envVars, are re-added on top.
            return executeResolved(codeFile, args, envVars, timeout, true);
        } catch (IOException e) {
            log.error("Failed to materialize inline code: {}", e.getMessage());
            return ScriptResult.error(-1, "Failed to write code file: " + e.getMessage());
        } finally {
            deleteQuietly(codeFile);
            deleteDirQuietly(scratchDir);
        }
    }

    private ScriptResult executeResolved(Path scriptPath, List<String> args, Map<String, String> envVars,
                                         long timeoutSeconds, boolean scrubSensitiveEnv) {
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            return ScriptResult.error(-1, "Script not found: " + scriptPath);
        }

        Process process = null;
        BoundedProcessOutput output = null;

        try {
            // 构建命令（结构化参数，避免 shell 注入）
            List<String> command = new ArrayList<>();

            // 根据文件扩展名选择解释器（跨平台适配）
            String fileName = scriptPath.getFileName().toString();
            if (fileName.endsWith(".py")) {
                // Windows 通常只有 python，没有 python3
                command.add(IS_WINDOWS ? "python" : "python3");
            } else if (fileName.endsWith(".sh")) {
                if (IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Shell scripts (.sh) are not supported on Windows. " +
                            "Consider providing a .bat or .ps1 alternative.");
                }
                command.add("bash");
            } else if (fileName.endsWith(".bat") || fileName.endsWith(".cmd")) {
                if (!IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Batch scripts (.bat/.cmd) are only supported on Windows.");
                }
                command.add("cmd.exe");
                command.add("/D");
                command.add("/C");
            } else if (fileName.endsWith(".ps1")) {
                command.add("powershell");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
            } else if (fileName.endsWith(".js")) {
                command.add("node");
            } else {
                if (!IS_WINDOWS && !Files.isExecutable(scriptPath)) {
                    return ScriptResult.error(-1, "Script not executable: " + fileName);
                }
            }

            command.add(scriptPath.toString());
            if (args != null) {
                command.addAll(args);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(scriptPath.getParent().toFile());
            // A skill is extension code, not part of the trusted server process.
            // Keep only the small interpreter/runtime allow-list, then inject
            // that skill's explicitly configured secrets below.
            if (scrubSensitiveEnv) {
                retainSafeInheritedEnvironment(pb.environment());
            }
            // Inject per-skill secrets / settings as env vars.
            // pb.environment() inherits the parent process env; putAll
            // OVERRIDES same-named entries with the supplied values.
            // Null / blank values are skipped to avoid clearing
            // legitimate parent env vars.
            if (envVars != null && !envVars.isEmpty()) {
                Map<String, String> processEnv = pb.environment();
                for (Map.Entry<String, String> e : envVars.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    if (e.getValue() == null) continue;
                    processEnv.put(e.getKey(), e.getValue());
                }
            }
            injectPipMirrorEnv(pb);

            process = pb.start();
            output = BoundedProcessOutput.start(process, MAX_OUTPUT_BYTES);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished && !output.exceeded()) ProcessTreeTerminator.kill(process);
            output.await();
            if (output.exceeded()) {
                return ScriptResult.error(-1,
                        "Process output exceeded " + MAX_OUTPUT_BYTES + " bytes");
            }
            if (!finished) {
                String stdout = output.stdout();
                String stderr = output.stderr();
                String timeoutMsg = "[timeout after " + timeoutSeconds + "s]";
                stderr = stderr.isEmpty() ? timeoutMsg : stderr + "\n" + timeoutMsg;
                return new ScriptResult(-1, stdout, stderr);
            }

            int exitCode = process.exitValue();
            String stdout = output.stdout();
            String stderr = output.stderr();
            return new ScriptResult(exitCode, stdout, stderr);

        } catch (InterruptedException e) {
            // Stop must terminate the OS process as well as the Java wait.
            // Without this, cancelling the outer chat stream leaves skill
            // scripts running until their normal timeout.
            if (process != null && process.isAlive()) {
                ProcessTreeTerminator.kill(process);
            }
            Thread.currentThread().interrupt();
            log.info("Skill script interrupted by conversation cancellation: {}", scriptPath);
            return ScriptResult.error(-1, "Execution cancelled by user");
        } catch (Exception e) {
            log.error("Failed to execute script {}: {}", scriptPath, e.getMessage());
            return ScriptResult.error(-1, "Execution error: " + e.getMessage());
        } finally {
            if (output != null) output.close();
        }
    }

    /**
     * Inject pip mirror config into the subprocess environment.
     *
     * <p>Three layers, later ones only fill gaps left by earlier ones:
     * <ol>
     *   <li>Explicit caller env — {@code PIP_INDEX_URL} / {@code PIP_TRUSTED_HOST}
     *       may be supplied as a skill-scoped value. Host environment values
     *       are scrubbed before this method runs so embedded credentials do not
     *       leak into LLM-authored code.</li>
     *   <li>Spring config fallback — for desktop (non-Docker) deployments where
     *       the host may not have those env vars. Injected only when absent.</li>
     *   <li>Auto-derive {@code PIP_TRUSTED_HOST} — if the index URL is plain
     *       HTTP and no trusted-host is set, pip blocks the download. Extract
     *       the host from the URL so the user only needs to set one variable.</li>
     * </ol>
     */
    private void injectPipMirrorEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();

        // Layer 2: Spring config fallback (desktop)
        if (pipIndexUrl != null && !pipIndexUrl.isBlank()
                && !env.containsKey("PIP_INDEX_URL")) {
            env.put("PIP_INDEX_URL", pipIndexUrl);
        }
        if (pipTrustedHost != null && !pipTrustedHost.isBlank()
                && !env.containsKey("PIP_TRUSTED_HOST")) {
            env.put("PIP_TRUSTED_HOST", pipTrustedHost);
        }

        // Layer 3: auto-derive trusted-host for HTTP sources
        String indexUrl = env.get("PIP_INDEX_URL");
        if (indexUrl != null && !indexUrl.isBlank()
                && !env.containsKey("PIP_TRUSTED_HOST")
                && indexUrl.startsWith("http://")) {
            String host = URI.create(indexUrl).getHost();
            if (host != null && !host.isEmpty()) {
                env.put("PIP_TRUSTED_HOST", host);
            }
        }
    }

    static void retainSafeInheritedEnvironment(Map<String, String> environment) {
        if (environment == null) return;
        Map<String, String> inheritedProxies = new java.util.HashMap<>();
        for (String key : PROXY_ENV) {
            String value = environment.get(key);
            if ("NO_PROXY".equals(key) ? isSafeNoProxy(value) : isUnauthenticatedProxy(value)) {
                inheritedProxies.put(key, value);
            }
        }
        environment.keySet().removeIf(key -> !SAFE_INHERITED_ENV.contains(key));
        // Keep connectivity through an operator's proxy only when its URL has
        // no user-info credentials. Skill-provided envVars are applied later
        // and may explicitly opt into an authenticated proxy.
        environment.putAll(inheritedProxies);
    }

    static boolean isUnauthenticatedProxy(String value) {
        if (value == null || value.isBlank() || value.contains("@")) return false;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "socks".equalsIgnoreCase(scheme)
                    || "socks5".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSafeNoProxy(String value) {
        return value != null && !value.isBlank() && value.length() <= 4096 && !value.contains("@");
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    /** Recursively remove a scratch directory created for an inline-code run. */
    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ScriptResult {
        private int exitCode;
        private String stdout;
        private String stderr;

        public static ScriptResult error(int code, String message) {
            return new ScriptResult(code, "", message);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
