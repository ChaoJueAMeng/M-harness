package com.mharness.agent;

import com.mharness.checkpoint.CheckpointService;
import com.mharness.context.ContextPacker;
import com.mharness.llm.ChatTurn;
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
import java.util.ArrayList;
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

    @Test
    void observerReceivesToolStatus() {
        List<String> seen = new ArrayList<>();
        AgentLoop loop = loop(
                PermissionMode.ASK,
                false,
                new ScriptedChatClient(
                        new LlmResponse("", List.of(new LlmToolCall("1", "delete_everything", "{}"))),
                        new LlmResponse("stopped", List.of())
                ),
                (toolName, status, chars) -> seen.add(toolName + ":" + status)
        );
        loop.run("do harm");
        assertThat(seen).containsExactly("delete_everything:UNKNOWN_TOOL");
    }

    @Test
    void cancelBeforeRunReturnsCancelled() {
        AgentLoop loop = loop(PermissionMode.ASK, false, new ScriptedChatClient(
                new LlmResponse("should not run", List.of())
        ));
        loop.cancel();
        AgentOutcome outcome = loop.run("hello");
        assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.CANCELLED);
        assertThat(outcome.text()).isEqualTo("已取消。");
    }

    @Test
    void priorTurnsAreIncludedInFirstModelCall() {
        ScriptedChatClient client = new ScriptedChatClient(new LlmResponse("later", List.of()));
        AgentLoop loop = loop(PermissionMode.ASK, false, client);
        loop.run("now", List.of(
                ChatTurn.user("original-task"),
                ChatTurn.assistant("old-answer", List.of())
        ));
        assertThat(client.calls()).isNotEmpty();
        assertThat(client.calls().getFirst()).extracting(ChatTurn::content)
                .contains("original-task", "old-answer", "now");
    }

    @Test
    void compactedPriorDropsMiddleTurnsFromModelCall() {
        List<ChatTurn> prior = new ArrayList<>();
        prior.add(ChatTurn.user("original-task"));
        prior.add(ChatTurn.assistant("ack-0", List.of()));
        for (int i = 1; i <= 20; i++) {
            prior.add(ChatTurn.user("mid-" + i));
            prior.add(ChatTurn.assistant("ans-" + i, List.of()));
        }
        ScriptedChatClient client = new ScriptedChatClient(new LlmResponse("later", List.of()));
        AgentLoop loop = loop(PermissionMode.ASK, false, client);
        loop.run("now", prior);
        assertThat(client.calls()).isNotEmpty();
        List<String> contents = client.calls().getFirst().stream()
                .filter(turn -> turn.role() != ChatTurn.Role.SYSTEM)
                .map(ChatTurn::content)
                .toList();
        assertThat(contents).contains("original-task", "mid-20", "ans-20", "now");
        assertThat(contents).doesNotContain("mid-1", "ans-1", "mid-2");
    }

    @Test
    void cancelAfterToolStopsBeforeNextModelCall() {
        AgentLoop[] holder = new AgentLoop[1];
        holder[0] = loop(
                PermissionMode.ASK,
                false,
                new ScriptedChatClient(
                        new LlmResponse("", List.of(new LlmToolCall("1", "delete_everything", "{}"))),
                        new LlmResponse("should not reach", List.of())
                ),
                (toolName, status, chars) -> holder[0].cancel()
        );
        AgentOutcome outcome = holder[0].run("do harm");
        assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.CANCELLED);
    }

    private AgentLoop loop(PermissionMode mode, boolean dryRun, ScriptedChatClient client) {
        return loop(mode, dryRun, client, AgentObserver.NONE);
    }

    private AgentLoop loop(PermissionMode mode, boolean dryRun, ScriptedChatClient client, AgentObserver observer) {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        ToolRegistry registry = new ToolRegistry(List.of(
                new ReadFileTool(guard),
                new SearchReplaceTool(guard),
                new WriteFileTool(guard)
        ));
        PermissionPolicy policy = new PermissionPolicy(mode, dryRun, true, new AutoApprovalService(true));
        ContextPacker packer = new ContextPacker(guard, AgentLimits.defaults(), mode, dryRun);
        return new AgentLoop(client, registry, policy, packer, new CheckpointService(guard), AgentLimits.defaults(), observer);
    }
}
