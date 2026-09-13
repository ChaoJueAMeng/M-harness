package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 在工作区根目录执行一条 shell 命令。工作目录锁定为仓库根，超时会杀掉整棵进程树。
 * 破坏性命令由 {@link com.mharness.permission.HardDeny} 在执行前拦截，本类不再重复判断。
 */
public final class RunTerminalTool implements AgentTool {
    private final WorkspaceGuard guard;
    private final long timeoutSeconds;

    /** 默认超时 120 秒。 */
    public RunTerminalTool(WorkspaceGuard guard) {
        this(guard, 120);
    }

    public RunTerminalTool(WorkspaceGuard guard, long timeoutSeconds) {
        this.guard = guard;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "run_terminal";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("在工作区根目录执行一条 shell 命令。工作目录被锁定为仓库根。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command", "要执行的命令")
                        .required("command")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        String command = JsonArgs.requiredText(JsonArgs.parse(arguments), "command");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("bash", "-lc", command);
        builder.directory(guard.workspace().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), buffer));
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            destroyTree(process);
            reader.join(1000);
            return ToolResult.error("TIMEOUT", "命令超时已被终止（" + timeoutSeconds + "s）: " + command);
        }
        reader.join(1000);
        String output = buffer.toString(StandardCharsets.UTF_8);
        if (output.length() > 16_000) {
            output = output.substring(0, 16_000) + "\n... truncated ...";
        }
        return ToolResult.ok("exit=" + process.exitValue() + "\n" + output);
    }

    /**
     * 尽量杀掉子进程：先 destroy 子孙，Windows 再补一次 {@code taskkill /T}，避免留下孤儿编译进程。
     */
    private static void destroyTree(Process process) {
        long pid = process.pid();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", Long.toString(pid))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // best-effort kill
        }
    }

    /** 把进程 stdout/stderr 合并流读进缓冲区，进程结束时忽略 IO 异常。 */
    private static void drain(InputStream in, ByteArrayOutputStream buffer) {
        try {
            in.transferTo(buffer);
        } catch (Exception ignored) {
            // process ended
        }
    }
}
