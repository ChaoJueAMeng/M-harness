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
