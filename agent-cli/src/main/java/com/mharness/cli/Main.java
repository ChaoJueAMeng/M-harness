package com.mharness.cli;

import com.mharness.agent.AgentOutcome;
import com.mharness.agent.AgentRuntime;
import com.mharness.checkpoint.Checkpoint;
import com.mharness.config.HarnessConfig;
import com.mharness.permission.ApprovalService;
import com.mharness.permission.AutoApprovalService;
import com.mharness.permission.ConsoleApprovalService;
import com.mharness.permission.PermissionMode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "m-harness",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "安全优先、可回滚的 Java Coding Agent Runtime",
        subcommands = {
                Main.AskCommand.class,
                Main.AgentCommand.class,
                Main.StatusCommand.class,
                Main.RollbackCommand.class
        }
)
public final class Main implements Callable<Integer> {
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    static Path workspace(Path override) {
        return override == null ? Path.of("").toAbsolutePath().normalize() : override.toAbsolutePath().normalize();
    }

    static int runPrompt(Path workspace, PermissionMode mode, boolean dryRun, boolean yes, String prompt) {
        AgentRuntime runtime = new AgentRuntime(workspace);
        HarnessConfig config = HarnessConfig.load(workspace);
        config.requireApiKey();
        ApprovalService approvals = yes ? new AutoApprovalService(true) : new ConsoleApprovalService();
        var loop = runtime.createLoop(
                config.baseUrl(),
                config.apiKey(),
                config.model(),
                mode,
                dryRun,
                yes,
                approvals,
                token -> {
                    System.out.print(token);
                    System.out.flush();
                }
        );
        AgentOutcome outcome = loop.run(prompt);
        System.out.println();
        if (outcome.status() == AgentOutcome.Status.COMPLETED) {
            if (outcome.text() != null && !outcome.text().isBlank()) {
                System.out.println(outcome.text());
            }
            return 0;
        }
        if (outcome.status() == AgentOutcome.Status.MAX_STEPS) {
            System.err.println(outcome.text());
            if (outcome.checkpoint() != null) {
                System.err.println("checkpoint 仍保留: " + outcome.checkpoint().checkpointId() + "，可用 m-harness rollback");
            }
            return 2;
        }
        System.err.println("失败: " + outcome.error());
        return 1;
    }

    @Command(name = "ask", description = "只读提问，不能改文件或跑命令")
    static final class AskCommand implements Callable<Integer> {
        @Option(names = "--workspace", description = "工作区目录")
        Path workspace;

        @Parameters(index = "0..*", paramLabel = "PROMPT", description = "用户任务")
        List<String> prompt;

        @Override
        public Integer call() {
            return runPrompt(workspace(workspace), PermissionMode.ASK, false, true, join(prompt));
        }
    }

    @Command(name = "agent", description = "可改文件并执行命令（默认需确认）")
    static final class AgentCommand implements Callable<Integer> {
        @Option(names = "--workspace", description = "工作区目录")
        Path workspace;

        @Option(names = "--yes", description = "自动批准写文件和 Shell")
        boolean yes;

        @Option(names = "--dry-run", description = "只预览写操作和 Shell，不落盘")
        boolean dryRun;

        @Parameters(index = "0..*", paramLabel = "PROMPT", description = "用户任务")
        List<String> prompt;

        @Override
        public Integer call() {
            return runPrompt(workspace(workspace), PermissionMode.AGENT, dryRun, yes, join(prompt));
        }
    }

    @Command(name = "status", description = "查看当前 checkpoint")
    static final class StatusCommand implements Callable<Integer> {
        @Option(names = "--workspace", description = "工作区目录")
        Path workspace;

        @Override
        public Integer call() {
            AgentRuntime runtime = new AgentRuntime(workspace(workspace));
            Checkpoint current = runtime.checkpoints().current();
            if (current == null) {
                System.out.println("没有 checkpoint");
                return 0;
            }
            System.out.printf("checkpointId=%s%nbaseCommit=%s%nsnapshotCommit=%s%ncreatedAt=%s%n",
                    current.checkpointId(),
                    current.baseCommit(),
                    current.snapshotCommit(),
                    current.createdAt());
            return 0;
        }
    }

    @Command(name = "rollback", description = "回滚到最近一次 checkpoint")
    static final class RollbackCommand implements Callable<Integer> {
        @Option(names = "--workspace", description = "工作区目录")
        Path workspace;

        @Override
        public Integer call() {
            AgentRuntime runtime = new AgentRuntime(workspace(workspace));
            Checkpoint current = runtime.checkpoints().current();
            runtime.checkpoints().rollback(current);
            System.out.println("已回滚" + (current == null ? "" : " " + current.checkpointId()));
            return 0;
        }
    }

    private static String join(List<String> prompt) {
        if (prompt == null || prompt.isEmpty()) {
            throw new CommandLine.ParameterException(new CommandLine(new Main()), "缺少 prompt");
        }
        return String.join(" ", prompt);
    }
}
