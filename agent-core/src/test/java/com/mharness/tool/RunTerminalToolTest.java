package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunTerminalToolTest {
    @TempDir
    Path workspace;

    @Test
    void timesOutAndKillsProcess() throws Exception {
        RunTerminalTool tool = new RunTerminalTool(new WorkspaceGuard(workspace), 2);
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "ping -t 127.0.0.1"
                : "sleep 30";
        long start = System.currentTimeMillis();
        ToolResult result = tool.execute("{\"command\":\"" + command.replace("\\", "\\\\") + "\"}");
        Thread.sleep(500);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("TIMEOUT");
        assertThat(elapsed).isLessThan(20_000);
    }

    @Test
    void runsInWorkspace() throws Exception {
        Files.writeString(workspace.resolve("marker.txt"), "ok");
        RunTerminalTool tool = new RunTerminalTool(new WorkspaceGuard(workspace), 15);
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "type marker.txt"
                : "cat marker.txt";
        ToolResult result = tool.execute("{\"command\":\"" + command + "\"}");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("ok");
    }
}
