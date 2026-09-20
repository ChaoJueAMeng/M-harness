package com.mharness.checkpoint;

import com.mharness.config.HarnessConfig;
import com.mharness.workspace.WorkspaceGuard;
import com.mharness.workspace.WorkspaceIgnore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用独立 git ref 保存工作区快照，而不是 git stash 或 reset。
 * 快照写在 {@code refs/m-harness/checkpoints/<id>}，包含当时磁盘上的文件（跳过 .git / target / node_modules）。
 * 工作区已有 {@code .git} 时写入用户仓库；否则写入配置目录下的 sidecar 裸仓库，不在用户目录初始化 git。
 * 任务成功删除 ref；失败或手动 rollback 时恢复快照并删掉其后新建的文件。
 */
public final class CheckpointService {
    /** checkpoint ref 的命名前缀，避免污染用户自己的分支。 */
    public static final String REF_PREFIX = "refs/m-harness/checkpoints/";
    /** 每个工作区最多保留的 checkpoint 数；步数上限 / 取消 / 失败都会留下 ref，需要有上界。 */
    public static final int MAX_RETAINED = 20;
    private final WorkspaceGuard guard;
    private final WorkspaceIgnore ignore;

    public CheckpointService(WorkspaceGuard guard) {
        this.guard = guard;
        this.ignore = WorkspaceIgnore.of(guard.workspace());
    }

    /**
     * 扫描工作区、写入 snapshot commit，并把 ref 指过去。
     * 不会改用户仓库的 HEAD，也不会 reset 工作区。写入后裁掉超出 {@link #MAX_RETAINED} 的旧快照。
     */
    public Checkpoint create() {
        try (Git git = openCheckpointGit(true)) {
            Repository repo = git.getRepository();
            if (hasUserGit()) {
                // 先读一次 status，确保仓库可读，顺带让 jgit 刷新索引状态。
                git.status().call();
            }
            Instant now = Instant.now();
            String id = newCheckpointId(now);
            ObjectId head = repo.resolve(Constants.HEAD);
            String base = head == null ? null : head.name();
            // 把当前磁盘文件打成一棵独立 tree/commit，不经过用户 index。
            ObjectId snapshot = writeSnapshotCommit(repo, head, id, now);
            RefUpdate update = repo.updateRef(REF_PREFIX + id);
            update.setNewObjectId(snapshot);
            update.setRefLogMessage("m-harness checkpoint", false);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                throw new CheckpointException("无法写入 checkpoint ref: " + result);
            }
            pruneOld(repo, id);
            return new Checkpoint(id, base, snapshot.name(), now, guard.workspace());
        } catch (CheckpointException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointException("创建 checkpoint 失败", e);
        }
    }

    /**
     * id 形如 {@code <毫秒时间戳 11 位十六进制>-<4 位随机>}：定宽时间前缀让 ref 名的字典序就是创建顺序，
     * 与 {@link #current()} 按 commit 时间排序互为兜底。
     */
    static String newCheckpointId(Instant when) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return String.format("%011x-%s", when.toEpochMilli(), random);
    }

    /** 删除指定 checkpoint 的 ref；null 时什么也不做。 */
    public void delete(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        delete(checkpoint.checkpointId());
    }

    /** 按 id 删除 {@code refs/m-harness/checkpoints/<id>}。 */
    public void delete(String checkpointId) {
        try (Git git = openCheckpointGit(false)) {
            if (git == null) {
                return;
            }
            RefUpdate update = git.getRepository().updateRef(REF_PREFIX + checkpointId);
            update.setForceUpdate(true);
            update.delete();
        } catch (Exception e) {
            throw new CheckpointException("删除 checkpoint 失败", e);
        }
    }

    /**
     * 读取当前仓库里「最近创建」的 checkpoint。
     * 按 snapshot commit 的提交时间排序，同一秒内再按 ref 名（带时间前缀）排序，而不是只看 ref 名字典序。
     * 没有 sidecar / 用户仓库或没有快照时返回 null。
     */
    public Checkpoint current() {
        try (Git git = openCheckpointGit(false)) {
            if (git == null) {
                return null;
            }
            List<Checkpoint> all = listCheckpoints(git.getRepository());
            return all.isEmpty() ? null : all.getFirst();
        } catch (RepositoryNotFoundException e) {
            return null;
        } catch (CheckpointException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointException("读取 checkpoint 失败", e);
        }
    }

    /** 全部 checkpoint，新的在前。解析失败的 ref 直接跳过。 */
    private List<Checkpoint> listCheckpoints(Repository repo) throws IOException {
        List<Ref> refs = repo.getRefDatabase().getRefsByPrefix(REF_PREFIX);
        List<Checkpoint> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : refs) {
                String id = ref.getName().substring(REF_PREFIX.length());
                try {
                    RevCommit commit = walk.parseCommit(ref.getObjectId());
                    String base = commit.getParentCount() > 0 ? commit.getParent(0).name() : null;
                    Instant when = commit.getCommitterIdent() == null
                            ? Instant.ofEpochSecond(commit.getCommitTime())
                            : commit.getCommitterIdent().getWhenAsInstant();
                    result.add(new Checkpoint(id, base, commit.name(), when, guard.workspace()));
                } catch (IOException ignored) {
                    // dangling or corrupt ref
                }
            }
        }
        result.sort(Comparator.comparing(Checkpoint::createdAt)
                .thenComparing(Checkpoint::checkpointId)
                .reversed());
        return result;
    }

    /** 只保留最近 {@link #MAX_RETAINED} 个 checkpoint；刚创建的那个永远保留。 */
    private void pruneOld(Repository repo, String keepId) throws IOException {
        List<Checkpoint> all = listCheckpoints(repo);
        for (int i = MAX_RETAINED; i < all.size(); i++) {
            Checkpoint stale = all.get(i);
            if (stale.checkpointId().equals(keepId)) {
                continue;
            }
            RefUpdate update = repo.updateRef(REF_PREFIX + stale.checkpointId());
            update.setForceUpdate(true);
            update.delete();
        }
    }

    /**
     * 把工作区强制恢复到快照内容。
     * checkpoint 为 null 时回滚「当前」快照；没有快照则抛错。
     * 除恢复快照文件外还会删除快照里不存在、之后新建的文件。
     */
    public void rollback(Checkpoint checkpoint) {
        if (checkpoint == null) {
            Checkpoint current = current();
            if (current == null) {
                throw new CheckpointException("没有可回滚的 checkpoint");
            }
            rollback(current);
            return;
        }
        try (Git git = openCheckpointGit(false)) {
            if (git == null) {
                throw new CheckpointException("找不到 snapshot: " + checkpoint.checkpointId());
            }
            Repository repo = git.getRepository();
            ObjectId snapshot = repo.resolve(checkpoint.snapshotCommit());
            if (snapshot == null) {
                snapshot = repo.resolve(REF_PREFIX + checkpoint.checkpointId());
            }
            if (snapshot == null) {
                throw new CheckpointException("找不到 snapshot: " + checkpoint.checkpointId());
            }
            Set<String> snapshotPaths = listTree(repo, snapshot);
            if (hasUserGit()) {
                // 强制把已跟踪路径恢复到快照内容。
                git.checkout()
                        .setStartPoint(snapshot.name())
                        .setAllPaths(true)
                        .setForced(true)
                        .call();
            } else {
                // sidecar 是裸仓库，不能 checkout 到用户目录；按 tree 把 blob 写回工作区。
                restoreSnapshotFiles(repo, snapshot);
            }
            // checkout / 写回都不会删掉快照之后新建的文件，需要再扫一遍磁盘。
            deleteExtras(snapshotPaths);
        } catch (CheckpointException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointException("回滚失败", e);
        }
    }

    /** 工作区是否自带 git 仓库（含 {@code .git} 文件或目录）。 */
    boolean hasUserGit() {
        return Files.exists(guard.workspace().resolve(".git"));
    }

    /** sidecar 裸仓库目录：{@code ~/.m-harness/checkpoints/<workspacePathSha256>}。 */
    Path sidecarGitDir() {
        return HarnessConfig.globalConfigDir().resolve("checkpoints").resolve(workspaceKey(guard.workspace()));
    }

    /**
     * 打开 checkpoint 所用的 Git：用户仓库或 sidecar。
     * {@code createSidecar} 为 false 且 sidecar 不存在时返回 null。
     */
    private Git openCheckpointGit(boolean createSidecar) throws Exception {
        if (hasUserGit()) {
            return Git.open(guard.workspace().toFile());
        }
        Path gitDir = sidecarGitDir();
        boolean exists = Files.exists(gitDir.resolve("HEAD")) || Files.exists(gitDir.resolve("objects"));
        if (!exists) {
            if (!createSidecar) {
                return null;
            }
            Files.createDirectories(gitDir);
            try (Git created = Git.init().setDirectory(gitDir.toFile()).setBare(true).call()) {
                created.getRepository().getConfig().unset("core", null, "worktree");
                created.getRepository().getConfig().save();
            }
        }
        return Git.open(gitDir.toFile());
    }

    static String workspaceKey(Path workspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(workspace.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 遍历工作区文件，写入 in-memory index，再生成 tree + commit。
     * 父提交为当时 HEAD，方便从 snapshot 看出基于哪次提交。
     */
    private ObjectId writeSnapshotCommit(Repository repo, ObjectId parent, String id, Instant when) throws IOException {
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            Path root = guard.workspace();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return enterDirectory(root, dir);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (WorkspaceIgnore.skipSnapshotFile(file, attrs)) {
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
            PersonIdent ident = new PersonIdent("m-harness", "m-harness@local", when, ZoneOffset.UTC);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("m-harness checkpoint " + id);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    /** 把 snapshot commit 的文件写回工作区，不通过 git checkout，避免 sidecar 在用户目录留下 .git。 */
    private void restoreSnapshotFiles(Repository repo, ObjectId snapshot) throws IOException {
        Path root = guard.workspace();
        try (RevWalk revWalk = new RevWalk(repo); TreeWalk treeWalk = new TreeWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(snapshot);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                Path target = root.resolve(treeWalk.getPathString()).normalize();
                if (!target.startsWith(root)) {
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                Files.write(target, loader.getBytes());
            }
        }
    }

    /** 列出 snapshot commit 树中的全部相对路径，供回滚时判断哪些文件是「多出来的」。 */
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

    /**
     * 删除工作区里存在、但快照树中没有的普通文件。
     * 必须与 {@link #writeSnapshotCommit} 使用同一份跳过规则：快照没拍的 {@code target} / {@code node_modules}
     * 和符号链接，这里也不能碰，否则回滚会把用户的依赖目录和链接当成「Agent 新建的文件」删掉。
     */
    private void deleteExtras(Set<String> snapshotPaths) throws IOException {
        Path root = guard.workspace();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return enterDirectory(root, dir);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (WorkspaceIgnore.skipSnapshotFile(file, attrs)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!snapshotPaths.contains(relative)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 快照与回滚共用的目录准入：跳过 {@link WorkspaceIgnore} 名单，以及 realpath 已逃出工作区的目录
     * （Windows junction 在 Java 里表现为普通目录，不加这一层会顺着它走到仓库外面去读写）。
     */
    private FileVisitResult enterDirectory(Path root, Path dir) {
        if (dir.equals(root)) {
            return FileVisitResult.CONTINUE;
        }
        if (ignore.skipDirectory(dir)) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        try {
            if (!dir.toRealPath().startsWith(root)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
        } catch (IOException e) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }
}
