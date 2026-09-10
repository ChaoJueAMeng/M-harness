package com.mharness.checkpoint;

import com.mharness.workspace.WorkspaceGuard;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CheckpointService {
    public static final String REF_PREFIX = "refs/m-harness/checkpoints/";
    private final WorkspaceGuard guard;

    public CheckpointService(WorkspaceGuard guard) {
        this.guard = guard;
    }

    public Checkpoint create() {
        Path gitDir = guard.workspace().resolve(".git");
        if (!Files.exists(gitDir)) {
            throw new CheckpointException("工作区不是 git 仓库");
        }
        try (Git git = Git.open(guard.workspace().toFile())) {
            git.status().call();
            Repository repo = git.getRepository();
            String id = UUID.randomUUID().toString().substring(0, 8);
            ObjectId head = repo.resolve(Constants.HEAD);
            String base = head == null ? null : head.name();
            ObjectId snapshot = writeSnapshotCommit(repo, head, id);
            RefUpdate update = repo.updateRef(REF_PREFIX + id);
            update.setNewObjectId(snapshot);
            update.setRefLogMessage("m-harness checkpoint", false);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                throw new CheckpointException("无法写入 checkpoint ref: " + result);
            }
            return new Checkpoint(id, base, snapshot.name(), Instant.now(), guard.workspace());
        } catch (CheckpointException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointException("创建 checkpoint 失败", e);
        }
    }

    public void delete(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        delete(checkpoint.checkpointId());
    }

    public void delete(String checkpointId) {
        try (Git git = Git.open(guard.workspace().toFile())) {
            RefUpdate update = git.getRepository().updateRef(REF_PREFIX + checkpointId);
            update.setForceUpdate(true);
            update.delete();
        } catch (Exception e) {
            throw new CheckpointException("删除 checkpoint 失败", e);
        }
    }

    public Checkpoint current() {
        try (Git git = Git.open(guard.workspace().toFile())) {
            List<Ref> refs = git.getRepository().getRefDatabase().getRefsByPrefix(REF_PREFIX);
            if (refs.isEmpty()) {
                return null;
            }
            Ref latest = refs.getLast();
            String id = latest.getName().substring(REF_PREFIX.length());
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(latest.getObjectId());
                String base = commit.getParentCount() > 0 ? commit.getParent(0).name() : null;
                return new Checkpoint(id, base, commit.name(), Instant.ofEpochSecond(commit.getCommitTime()), guard.workspace());
            }
        } catch (Exception e) {
            throw new CheckpointException("读取 checkpoint 失败", e);
        }
    }

    public void rollback(Checkpoint checkpoint) {
        if (checkpoint == null) {
            Checkpoint current = current();
            if (current == null) {
                throw new CheckpointException("没有可回滚的 checkpoint");
            }
            rollback(current);
            return;
        }
        try (Git git = Git.open(guard.workspace().toFile())) {
            Repository repo = git.getRepository();
            ObjectId snapshot = repo.resolve(checkpoint.snapshotCommit());
            if (snapshot == null) {
                snapshot = repo.resolve(REF_PREFIX + checkpoint.checkpointId());
            }
            if (snapshot == null) {
                throw new CheckpointException("找不到 snapshot: " + checkpoint.checkpointId());
            }
            Set<String> snapshotPaths = listTree(repo, snapshot);
            git.checkout()
                    .setStartPoint(snapshot.name())
                    .setAllPaths(true)
                    .setForced(true)
                    .call();
            deleteExtras(snapshotPaths);
        } catch (CheckpointException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointException("回滚失败", e);
        }
    }

    private ObjectId writeSnapshotCommit(Repository repo, ObjectId parent, String id) throws IOException {
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            Path root = guard.workspace();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(file);
                    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, bytes);
                    DirCacheEntry entry = new DirCacheEntry(relative);
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(blob);
                    builder.add(entry);
                    return FileVisitResult.CONTINUE;
                }
            });
            builder.finish();
            ObjectId treeId = index.writeTree(inserter);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (parent != null) {
                commit.setParentId(parent);
            }
            PersonIdent ident = new PersonIdent("m-harness", "m-harness@local");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("m-harness checkpoint " + id);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    private static Set<String> listTree(Repository repo, ObjectId commitId) throws IOException {
        Set<String> paths = new HashSet<>();
        try (RevWalk revWalk = new RevWalk(repo); TreeWalk treeWalk = new TreeWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                paths.add(treeWalk.getPathString());
            }
        }
        return paths;
    }

    private void deleteExtras(Set<String> snapshotPaths) throws IOException {
        Path root = guard.workspace();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (name.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!snapshotPaths.contains(relative)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
