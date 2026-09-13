package com.mharness.checkpoint;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 一次工作区快照的元数据。
 *
 * @param checkpointId   短 id，对应 {@code refs/m-harness/checkpoints/<id>}
 * @param baseCommit     拍快照时的 HEAD commit，仓库为空时可能为 null
 * @param snapshotCommit 快照 commit 的完整 SHA
 * @param createdAt      创建时间
 * @param workspace      所属工作区根目录
 */
public record Checkpoint(
        String checkpointId,
        String baseCommit,
        String snapshotCommit,
        Instant createdAt,
        Path workspace
) {
}
