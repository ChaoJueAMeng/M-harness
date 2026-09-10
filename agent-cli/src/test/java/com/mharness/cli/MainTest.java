package com.mharness.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {
    @Test
    void printsUsageWithoutArgs() {
        CommandLine cmd = new CommandLine(new Main());
        String help = cmd.getUsageMessage();
        assertThat(help).contains("ask");
        assertThat(help).contains("agent");
        assertThat(help).contains("status");
        assertThat(help).contains("rollback");
    }
}
