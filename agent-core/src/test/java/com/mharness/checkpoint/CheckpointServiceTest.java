package com.mharness.checkpoint;

import com.mharness.workspace.WorkspaceGuard;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointServiceTest {
    @TempDir
    Path workspace;

    @Test
    void snapshotDoesNotResetWorktreeAndRollbackRestoresNewFiles() throws Exception {
        try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
            Files.writeString(workspace.resolve("keep.txt"), "original");
            git.add().addFilepattern("keep.txt").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call();
            Files.writeString(workspace.resolve("wip.txt"), "user-wip");
            CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
            Checkpoint checkpoint = service.create();
            assertThat(Files.readString(workspace.resolve("wip.txt"))).isEqualTo("user-wip");
            assertThat(git.getRepository().exactRef("refs/stash")).isNull();
            Files.writeString(workspace.resolve("keep.txt"), "changed");
            Files.writeString(workspace.resolve("new.txt"), "created-by-agent");
            service.rollback(checkpoint);
            assertThat(Files.readString(workspace.resolve("keep.txt"))).isEqualTo("original");
            assertThat(Files.readString(workspace.resolve("wip.txt"))).isEqualTo("user-wip");
            assertThat(Files.exists(workspace.resolve("new.txt"))).isFalse();
            Repository repo = git.getRepository();
            assertThat(repo.resolve("HEAD").name()).isEqualTo(checkpoint.baseCommit());
        }
    }

    @Test
    void currentReturnsNullWhenNotAGitRepo() {
        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        assertThat(service.current()).isNull();
    }

    @Test
    void currentReturnsNullWhenGitRepoHasNoCheckpoint() throws Exception {
        try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
            Files.writeString(workspace.resolve("keep.txt"), "original");
            git.add().addFilepattern("keep.txt").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call();
            CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
            assertThat(service.current()).isNull();
        }
    }
}
