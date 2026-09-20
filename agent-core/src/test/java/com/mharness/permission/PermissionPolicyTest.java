package com.mharness.permission;

import com.mharness.tool.AgentTool;
import com.mharness.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionPolicyTest {
    @Test
    void askModeDeniesWrites() {
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.ASK, false, true, new AutoApprovalService(true));
        assertThat(policy.evaluate(named("read_file"), "{}")).isEqualTo(PermissionDecision.ALLOW);
        assertThat(policy.evaluate(named("search_replace"), "{}")).isEqualTo(PermissionDecision.DENY_ASK_MODE);
        assertThat(policy.evaluate(named("run_terminal"), "{\"command\":\"mvn test\"}"))
                .isEqualTo(PermissionDecision.DENY_ASK_MODE);
    }

    @Test
    void dryRunDoesNotExecuteMutations() {
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.AGENT, true, true, new AutoApprovalService(true));
        assertThat(policy.evaluate(named("write_file"), "{}")).isEqualTo(PermissionDecision.DRY_RUN);
        assertThat(policy.evaluate(named("read_file"), "{}")).isEqualTo(PermissionDecision.ALLOW);
    }

    @Test
    void hardDenyBeatsYes() {
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.AGENT, false, true, new AutoApprovalService(true));
        assertThat(policy.evaluate(named("run_terminal"), "{\"command\":\"git push --force\"}"))
                .isEqualTo(PermissionDecision.HARD_DENY);
    }

    @Test
    void hardDenySeesTheSameCommandAsTheShell() {
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.AGENT, false, true, new AutoApprovalService(true));
        // 转义引号让手写截取提前结束；必须与 RunTerminalTool 的 Jackson 解析看到同一条命令。
        String escaped = "{\"command\":\"echo \\\"hi\\\" && git push --force origin main\"}";
        assertThat(PermissionPolicy.extractCommand(escaped)).isEqualTo("echo \"hi\" && git push --force origin main");
        assertThat(policy.evaluate(named("run_terminal"), escaped)).isEqualTo(PermissionDecision.HARD_DENY);

        String unicode = "{\"command\":\"git \\u0070ush --force\"}";
        assertThat(policy.evaluate(named("run_terminal"), unicode)).isEqualTo(PermissionDecision.HARD_DENY);

        String otherFieldFirst = "{\"cwd\":\"x\",\"command\":\"git reset --hard\"}";
        assertThat(policy.evaluate(named("run_terminal"), otherFieldFirst)).isEqualTo(PermissionDecision.HARD_DENY);
    }

    @Test
    void malformedArgumentsFallBackToWholeText() {
        assertThat(PermissionPolicy.extractCommand("not json git push --force")).isEqualTo("not json git push --force");
        assertThat(PermissionPolicy.extractCommand("{\"other\":1}")).isEqualTo("{\"other\":1}");
        assertThat(PermissionPolicy.extractCommand(null)).isEmpty();
    }

    private static AgentTool named(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolSpecification specification() {
                return ToolSpecification.builder().name(name).build();
            }

            @Override
            public ToolResult execute(String arguments) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
