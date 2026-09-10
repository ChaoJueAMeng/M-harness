package com.mharness.agent;

import com.mharness.checkpoint.CheckpointService;
import com.mharness.context.ContextPacker;
import com.mharness.llm.LlmResponse;
import com.mharness.llm.LlmToolCall;
import com.mharness.llm.ScriptedChatClient;
import com.mharness.permission.AutoApprovalService;
import com.mharness.permission.PermissionMode;
import com.mharness.permission.PermissionPolicy;
import com.mharness.tool.ReadFileTool;
import com.mharness.tool.SearchReplaceTool;
import com.mharness.tool.ToolRegistry;
import com.mharness.tool.ToolResult;
import com.mharness.tool.WriteFileTool;
import com.mharness.workspace.WorkspaceGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {
    @TempDir
    Path workspace;

    @Test
    void unknownToolIsReturnedToModel() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "hello");
        AgentLoop loop = loop(PermissionMode.ASK, false, new ScriptedChatClient(
                new LlmResponse("", List.of(new LlmToolCall("1", "delete_everything", "{}"))),
                new LlmResponse("stopped", List.of())
        ));
        AgentOutcome outcome = loop.run("do harm");
        assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo("stopped");
    }

    @Test
    void askModeDoesNotWrite() throws Exception {
        Files.writeString(workspace.resolve("A.txt"), "old");
        AgentLoop loop = loop(PermissionMode.ASK, false, new ScriptedChatClient(
                new LlmResponse("", List.of(new LlmToolCall(
                        "1",
                        "search_replace",
                        "{\"path\":\"A.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}"
                ))),
                new LlmResponse("cannot write", List.of())
        ));
        loop.run("change it");
        assertThat(Files.readString(workspace.resolve("A.txt"))).isEqualTo("old");
    }

    @Test
    void dryRunDoesNotWrite() throws Exception {
        Files.writeString(workspace.resolve("A.txt"), "old");
        AgentLoop loop = loop(PermissionMode.AGENT, true, new ScriptedChatClient(
                new LlmResponse("", List.of(new LlmToolCall(
                        "1",
                        "write_file",
                        "{\"path\":\"B.txt\",\"content\":\"x\"}"
                ))),
                new LlmResponse("previewed", List.of())
        ));
        loop.run("add file");
        assertThat(Files.exists(workspace.resolve("B.txt"))).isFalse();
        assertThat(Files.readString(workspace.resolve("A.txt"))).isEqualTo("old");
    }

    @Test
    void executeOneUnknownTool() {
        AgentLoop loop = loop(PermissionMode.AGENT, true, new ScriptedChatClient());
        ToolResult result = loop.executeOne(new LlmToolCall("1", "delete_everything", "{}"));
        assertThat(result.error()).isEqualTo("UNKNOWN_TOOL");
    }

    private AgentLoop loop(PermissionMode mode, boolean dryRun, ScriptedChatClient client) {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        ToolRegistry registry = new ToolRegistry(List.of(
                new ReadFileTool(guard),
                new SearchReplaceTool(guard),
                new WriteFileTool(guard)
        ));
        PermissionPolicy policy = new PermissionPolicy(mode, dryRun, true, new AutoApprovalService(true));
        ContextPacker packer = new ContextPacker(guard, AgentLimits.defaults(), mode, dryRun);
        return new AgentLoop(client, registry, policy, packer, new CheckpointService(guard), AgentLimits.defaults());
    }
}
