package com.mharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpApprovalServiceTest {
    @Test
    void denyAllRejectsPending() throws Exception {
        EventLog events = new EventLog();
        HttpApprovalService service = new HttpApprovalService(events, new ObjectMapper());
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> service.approve("run_terminal", "{}"));
        Thread.sleep(80);
        service.denyAll();
        assertThat(approved.get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void unknownRequestIdIsNotCompleted() {
        EventLog events = new EventLog();
        HttpApprovalService service = new HttpApprovalService(events, new ObjectMapper());
        assertThat(service.complete("missing", true)).isFalse();
    }
}
