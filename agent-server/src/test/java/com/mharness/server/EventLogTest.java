package com.mharness.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogTest {
    @Test
    void replayAndFinish() throws Exception {
        EventLog log = new EventLog();
        log.emitJson("{\"type\":\"token\"}");
        log.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        log.stream(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("data: {\"type\":\"token\"}");
    }
}
