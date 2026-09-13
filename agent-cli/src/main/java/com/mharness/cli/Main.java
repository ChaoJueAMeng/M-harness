package com.mharness.cli;

import com.mharness.agent.AgentObserver;
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

/**
 * m-harness 命令行入口。子命令：ask / agent / status / rollback。
 * 无子命令时打印帮助。
 */
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
    /** 用户只敲了 {@code m-harness} 时打印 usage。 */
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    /** {@code --workspace} 未给时用当前工作目录。 */
    static Path workspace(Path override) {
        return override == null ? Path.of("").toAbsolutePath().normalize() : override.toAbsolutePath().normalize();
    }

    /**
     * ask / agent 共用：加载配置、组装 AgentLoop、跑任务、按结局返回退出码。
     * 退出码：0 成功，1 失败，2 步数上限，130 取消。
     */
    static int runPrompt(Path workspace, PermissionMode mode, boolean dryRun, boolean yes, String prompt) {
        AgentRuntime runtime = new AgentRuntime(workspace);
        HarnessConfig config = HarnessConfig.load(workspace);
        config.requireApiKey();
        ApprovalService approvals = yes ? new AutoApprovalService(true) : new ConsoleApprovalService();
        AgentObserver observer = (toolName, status, chars) ->
                System.err.printf("[m-harness] %s %s (%d chars)%n", toolName, status, chars);
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
                },
                observer
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
        if (outcome.status() == AgentOutcome.Status.CANCELLED) {
            System.err.println(outcome.text() == null ? "已取消。" : outcome.text());
            return 130;
        }
        System.err.println("失败: " + outcome.error());
        return 1;
    }

    /** 只读提问：PermissionMode.ASK，且 autoApprove=true（Ask 本身不会执行变更工具）。 */
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

    /** 可写文件和跑命令；默认控制台确认，{@code --yes} 自动批准，{@code --dry-run} 只预览。 */
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

    /** 打印当前 checkpoint 元数据；没有则提示。 */
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

    /** 回滚到最近一次 checkpoint（含删除其后新建的文件）。 */
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

    /** 把剩余位置参数拼成一句 prompt；缺失时让 picocli 报错。 */
    private static String join(List<String> prompt) {
        if (prompt == null || prompt.isEmpty()) {
            throw new CommandLine.ParameterException(new CommandLine(new Main()), "缺少 prompt");
        }
        return String.join(" ", prompt);
    }
}
