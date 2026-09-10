# M-harness

安全优先、可回滚、支持 OpenAI-compatible API 的最小 Java Coding Agent Runtime。

模型提出行动后，必须经过权限策略和 WorkspaceGuard，才能读写工作区或执行命令。Agent 启动时会把当前工作区拍成独立 git ref；失败可回滚。

## 要求

- JDK 21（IntelliJ 可选择 `ms-21` / `jbr-21`）
- 工作区是 git 仓库（`agent` 模式需要 checkpoint）
- OpenAI 兼容接口的 API Key

本仓库包含 Maven Wrapper，不必全局安装 Maven。

## 配置

复制 `.env.example` 为 `.env`（不要提交 `.env`）：

```env
M_HARNESS_BASE_URL=https://api.openai.com/v1
M_HARNESS_API_KEY=sk-...
M_HARNESS_MODEL=gpt-4o-mini
```

DeepSeek 示例：

```env
M_HARNESS_BASE_URL=https://api.deepseek.com/v1
M_HARNESS_API_KEY=sk-...
M_HARNESS_MODEL=deepseek-chat
```

PowerShell 也可以临时设置：

```powershell
$env:M_HARNESS_API_KEY="sk-..."
$env:M_HARNESS_BASE_URL="https://api.deepseek.com/v1"
$env:M_HARNESS_MODEL="deepseek-chat"
```

## 构建

在 IntelliJ 中把项目 JDK 设为 21，或在终端指定 `JAVA_HOME`：

```powershell
$env:JAVA_HOME="C:\Users\caiji\.jdks\ms-21.0.12"
.\mvnw.cmd -pl agent-cli -am package
```

产物：`agent-cli/target/m-harness-cli.jar`

## 命令

```powershell
java -jar agent-cli/target/m-harness-cli.jar ask "这个仓库做什么"
java -jar agent-cli/target/m-harness-cli.jar agent "修复这个 bug"
java -jar agent-cli/target/m-harness-cli.jar agent --dry-run "把登录 bug 修一下"
java -jar agent-cli/target/m-harness-cli.jar agent --yes "运行测试并修失败用例"
java -jar agent-cli/target/m-harness-cli.jar status
java -jar agent-cli/target/m-harness-cli.jar rollback
```

`--workspace` 可指向目标仓库。Ask 只读。Agent 默认会询问写文件和 Shell；`--yes` 自动批准。`--dry-run` 只预览写操作和命令。

## 工具

`glob`、`grep`、`read_file`、`search_replace`、`write_file`（仅新建）、`run_terminal`。

`search_replace` 要求旧字符串唯一；不唯一时返回最多 5 处行号。Shell 工作目录锁定在仓库根，超时会强制结束。

## Checkpoint

快照写在 `refs/m-harness/checkpoints/<id>`，不会使用 `git stash`，也不会在启动时 reset 工作区。任务成功后删除该 ref；失败或手动 `rollback` 时恢复到快照（包括删除其后新建的文件）。
