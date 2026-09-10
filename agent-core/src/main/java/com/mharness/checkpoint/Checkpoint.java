package com.mharness.checkpoint;

import java.nio.file.Path;
import java.time.Instant;

public record Checkpoint(
        String checkpointId,
        String baseCommit,
        String snapshotCommit,
        Instant createdAt,
        Path workspace
) {
}
