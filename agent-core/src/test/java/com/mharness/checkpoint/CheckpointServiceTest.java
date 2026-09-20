package com.mharness.checkpoint;

import com.mharness.config.HarnessConfig;
import com.mharness.workspace.WorkspaceGuard;
import com.mharness.workspace.WorkspaceIgnore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointServiceTest {
    @TempDir
    Path workspace;

    @TempDir
    Path configDir;

    @BeforeEach
    void isolateGlobalConfig() {
        System.setProperty(HarnessConfig.CONFIG_DIR_PROPERTY, configDir.toString());
    }

    @AfterEach
    void clearGlobalConfigOverride() {
        System.clearProperty(HarnessConfig.CONFIG_DIR_PROPERTY);
    }

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
            assertThat(Files.exists(service.sidecarGitDir())).isFalse();
        }
    }

    @Test
    void rollbackKeepsIgnoredDirectoriesAndSymlinks(@TempDir Path outside) throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "original");
        Path classFile = workspace.resolve("target/classes/App.class");
        Path dependency = workspace.resolve("node_modules/pkg/index.js");
        Files.createDirectories(classFile.getParent());
        Files.createDirectories(dependency.getParent());
        Files.writeString(classFile, "bytecode");
        Files.writeString(dependency, "module.exports = 1");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("link-to-outside");
        boolean linked = tryLink(link, outside);

        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        Checkpoint checkpoint = service.create();
        // 快照之后依赖目录 / 构建产物又有了新文件：回滚不应把它们当成 Agent 新建的文件删掉。
        Files.writeString(workspace.resolve("target/classes/New.class"), "bytecode");
        Files.writeString(workspace.resolve("node_modules/pkg/extra.js"), "module.exports = 2");
        Files.writeString(workspace.resolve("agent-made.txt"), "created-by-agent");
        service.rollback(checkpoint);

        assertThat(classFile).exists();
        assertThat(dependency).exists();
        assertThat(workspace.resolve("target/classes/New.class")).exists();
        assertThat(workspace.resolve("node_modules/pkg/extra.js")).exists();
        assertThat(workspace.resolve("agent-made.txt")).doesNotExist();
        assertThat(outside.resolve("secret.txt")).hasContent("secret");
        if (linked) {
            assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        }
    }

    @Test
    void snapshotSkipsLargeAndBinaryFiles() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "ok");
        Files.write(workspace.resolve("blob.bin"), new byte[100]);
        Files.write(workspace.resolve("huge.dat"), new byte[(int) WorkspaceIgnore.MAX_SNAPSHOT_BYTES + 1]);
        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        Checkpoint checkpoint = service.create();
        Files.writeString(workspace.resolve("keep.txt"), "changed");
        Files.writeString(workspace.resolve("blob.bin"), "new-bin");
        Files.writeString(workspace.resolve("huge.dat"), "new-huge");
        service.rollback(checkpoint);
        assertThat(Files.readString(workspace.resolve("keep.txt"))).isEqualTo("ok");
        assertThat(Files.readString(workspace.resolve("blob.bin"))).isEqualTo("new-bin");
        assertThat(Files.readString(workspace.resolve("huge.dat"))).isEqualTo("new-huge");
    }

    @Test
    void currentPicksNewestByTimeNotByRefName() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "original");
        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        Checkpoint real = service.create();
        // 手工塞一个 ref 名字典序更大、但提交时间更早的快照，模拟旧格式的随机 id。
        try (Git git = Git.open(service.sidecarGitDir().toFile())) {
            Repository repo = git.getRepository();
            ObjectId older = writeEmptyCommit(repo, Instant.now().minusSeconds(3600));
            RefUpdate update = repo.updateRef(CheckpointService.REF_PREFIX + "zzzzzzzz");
            update.setNewObjectId(older);
            update.update();
            assertThat(repo.getRefDatabase().getRefsByPrefix(CheckpointService.REF_PREFIX)).hasSize(2);
        }
        Checkpoint current = service.current();
        assertThat(current).isNotNull();
        assertThat(current.checkpointId()).isEqualTo(real.checkpointId());
    }

    @Test
    void checkpointIdsSortChronologically() {
        String earlier = CheckpointService.newCheckpointId(Instant.parse("2026-01-01T00:00:00Z"));
        String later = CheckpointService.newCheckpointId(Instant.parse("2026-01-01T00:00:00.001Z"));
        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(earlier).matches("[0-9a-f]{11}-[0-9a-f]{4}");
    }

    @Test
    void createPrunesCheckpointsBeyondRetention() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "original");
        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        Checkpoint latest = null;
        for (int i = 0; i < CheckpointService.MAX_RETAINED + 3; i++) {
            latest = service.create();
        }
        try (Git git = Git.open(service.sidecarGitDir().toFile())) {
            assertThat(git.getRepository().getRefDatabase().getRefsByPrefix(CheckpointService.REF_PREFIX))
                    .hasSize(CheckpointService.MAX_RETAINED);
        }
        assertThat(service.current()).isNotNull();
        assertThat(service.current().checkpointId()).isEqualTo(latest.checkpointId());
    }

    private static boolean tryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            try {
                Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                process.waitFor();
                return Files.exists(link);
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static ObjectId writeEmptyCommit(Repository repo, Instant when) throws Exception {
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            ObjectId tree = inserter.insert(Constants.OBJ_TREE, new byte[0]);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            PersonIdent ident = new PersonIdent("t", "t@t", when, ZoneOffset.UTC);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("older");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
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

    @Test
    void sidecarSnapshotDoesNotCreateDotGitAndRollbackRestoresFiles() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "original");
        Files.writeString(workspace.resolve("wip.txt"), "user-wip");
        CheckpointService service = new CheckpointService(new WorkspaceGuard(workspace));
        assertThat(service.hasUserGit()).isFalse();

        Checkpoint checkpoint = service.create();
        assertThat(Files.exists(workspace.resolve(".git"))).isFalse();
        assertThat(Files.isRegularFile(workspace.resolve(".git"))).isFalse();
        assertThat(Files.exists(service.sidecarGitDir().resolve("HEAD"))).isTrue();
        assertThat(service.current()).isNotNull();
        assertThat(service.current().checkpointId()).isEqualTo(checkpoint.checkpointId());

        Files.writeString(workspace.resolve("keep.txt"), "changed");
        Files.writeString(workspace.resolve("new.txt"), "created-by-agent");
        service.rollback(checkpoint);

        assertThat(Files.readString(workspace.resolve("keep.txt"))).isEqualTo("original");
        assertThat(Files.readString(workspace.resolve("wip.txt"))).isEqualTo("user-wip");
        assertThat(Files.exists(workspace.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(workspace.resolve(".git"))).isFalse();
        assertThat(Files.isRegularFile(workspace.resolve(".git"))).isFalse();
    }
}
