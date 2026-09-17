# M Bot

安全优先、可回滚、支持 OpenAI-compatible API 的本地 Coding Agent（当前版本 `0.1.0`）。

模型提出行动后，必须经过权限策略和 `WorkspaceGuard`，才能读写工作区或执行命令。Agent 模式在真正改文件前会把工作区拍成独立 git ref（用户仓库或配置目录里的 sidecar）；失败、取消或手动回滚可恢复。

提供三种用法：

- **Windows 桌面端**（WinUI 3）：新建对话、Ask / Agent、批准框、设置、回滚
- **CLI**：`ask` / `agent` / `status` / `rollback`
- **本机 HTTP 服务**：只监听 `127.0.0.1`，供桌面端调用

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| `agent-core` | 运行时：Agent 循环、工具、权限、checkpoint、OpenAI 兼容客户端 |
| `agent-cli` | 命令行入口，产物 `m-harness-cli.jar` |
| `agent-server` | 本机 HTTP API，产物 `m-harness-server.jar` |
| `agent-desktop` | unpackaged WinUI 3 窗口（不在 Maven 模块里） |
| `installer/` | Inno Setup 脚本与绿色版安装/卸载脚本 |
| `scripts/publish-desktop.ps1` | 打包自包含桌面端 + 可选 Setup.exe |

Maven 模块只有 `agent-core`、`agent-cli`、`agent-server`。仓库自带 Maven Wrapper，不必全局安装 Maven。

## 要求

- JDK 21（IntelliJ 可选用 `ms-21` / `jbr-21`）
- OpenAI 兼容接口的 API Key（OpenAI、DeepSeek 等）
- 从源码跑桌面端另需 .NET 8 SDK（`dotnet --list-sdks`）
- 生成 Setup.exe 还需 [Inno Setup 6](https://jrsoftware.org/isinfo.php)

Ask 和 Agent 都可以在非 git 目录里运行。checkpoint 用内嵌 JGit：用户目录已有 `.git` 时写入该仓库的 ref，否则写入 `~/.m-harness/checkpoints/`，不会在用户目录 `git init`。安装包用户不必再装 JDK、.NET SDK 或 Git。

## 配置

模型设置是**全局**的：桌面端点一次「设置」，所有工作区共用。配置写在用户目录 `~/.m-harness/.env`（Windows 为 `%USERPROFILE%\.m-harness\.env`），不要提交这个文件。模板见仓库根目录 `.env.example`。

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

加载顺序：全局 `~/.m-harness/.env` → 工作区 `.env`（旧配置回退）→ 当前目录 `.env` → 进程环境变量 → 内置默认值。可用 `M_HARNESS_CONFIG_DIR` 改全局配置目录。

PowerShell 也可临时覆盖：

```powershell
$env:M_HARNESS_API_KEY="sk-..."
$env:M_HARNESS_BASE_URL="https://api.deepseek.com/v1"
$env:M_HARNESS_MODEL="deepseek-chat"
```

## 安全模型

- **WorkspaceGuard**：相对路径解析到工作区内；已存在路径走 realpath，阻止 `../` 和符号链接逃出仓库
- **Ask**：只读（`glob` / `grep` / `read_file`），写文件和 Shell 直接拒绝
- **Agent**：可写文件、跑命令；默认弹出批准框。CLI `--yes` 或桌面「自动批准」可跳过确认
- **Dry-run**：变更工具只返回预览，不落盘，也不拍 checkpoint
- **HardDeny**：`git push --force` / `git reset --hard` / `git clean -fd` / `rm /` / `format` / `shutdown` / `reboot` 一律禁止，不能被自动批准覆盖
- **Checkpoint**：真正会改工作区时才拍快照；运行时异常会尝试自动回滚
- **本机服务**：只允许绑定 `127.0.0.1` / `localhost`，请求必须带启动时随机生成的 Bearer token；同一时刻只跑一个任务

发给模型的系统提示会注入工作区摘要、浅层文件树，以及根目录 `AGENTS.md`（若存在）。`AGENTS.md` 被标为 untrusted，不能覆盖上述安全规则。

默认上限：最多 20 步、约 10 万输入 token、单条工具结果约 8k token、历史最多 40 条。取消是协作式的：正在进行的模型调用不会被打断，但下一轮步/工具不再执行。

## 构建

在 IntelliJ 中把项目 JDK 设为 21，或在终端指定 `JAVA_HOME`（IntelliJ 安装的 JDK 一般在 `%USERPROFILE%\.jdks\`）：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-21.0.12"
.\mvnw.cmd -pl agent-cli,agent-server -am package
```

产物：

- `agent-cli/target/m-harness-cli.jar`
- `agent-server/target/m-harness-server.jar`

跑测试：

```powershell
.\mvnw.cmd test
```

## 桌面端（WinUI 3）

需要 JDK 21、.NET 8 SDK。窗口会拉起本机 Java 服务（只听 `127.0.0.1`，端口由系统分配），关掉窗口时结束整棵 Java 进程树。

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-21.0.12"
.\mvnw.cmd -pl agent-server -am package
dotnet run --project agent-desktop\MHarness.Desktop.csproj -c Debug -p:Platform=x64
```

这是 WinUI 窗口程序，终端里通常没有日志。成功时会弹出标题为 **M Bot** 的窗口，关掉窗口后 `dotnet run` 才会结束。若立刻回到提示符，说明进程秒退，可直接运行：

```powershell
.\agent-desktop\bin\x64\Debug\net8.0-windows10.0.19041.0\win-x64\MHarness.Desktop.exe
```

窗口会自动查找 JDK 21：先看 exe 旁的 `jre`（安装包自带），再看 `JAVA_HOME`，再看 `%USERPROFILE%\.jdks\`，最后才用 PATH 上版本 ≥ 21 的 `java`。不要依赖 PATH 上的 Java 8。

窗口会自动查找 `m-harness-server.jar`：环境变量 `M_HARNESS_SERVER_JAR` → exe 旁 → 向上查找 `agent-server/target/m-harness-server.jar`。

窗口里可以：

- **新建对话** 先进入选择页：从空白开始，或使用已有文件夹。空白对话的 Ask / Agent 写到 `~/.m-harness/scratch/<对话 id>`；之后仍可用上方工作区芯片绑定真实项目（不自动搬运临时文件）
- 用 **Ask**（只读）或 **Agent**（可改文件）发送任务
- 勾选 **Dry-run** 只预览写操作和 Shell
- 勾选 **自动批准** 跳过写文件 / Shell 确认框（HardDeny 仍然生效）
- 缺 API Key 时点「设置」，写入全局 `~/.m-harness/.env`（界面不回显完整 Key，所有工作区共用）
- Agent 模式下写文件和 Shell 会弹出批准框；可随时点「停止」取消当前任务
- 「回滚」把工作区恢复到最近一次 checkpoint
- 左侧对话区看模型流式输出，右侧工具日志看每次工具执行

## 安装包（Windows x64）

不要做成 MSIX：Agent 要在用户任选的目录里读写文件、执行 Shell，MSIX 沙箱会拦住 Java 子进程。正确做法是 **unpackaged 自包含发布 + 捆绑 JRE + Inno Setup**。

本机需要 JDK 21（用于 `jlink`）、.NET 8 SDK。生成 Setup.exe 还需 Inno Setup 6。

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-21.0.12"
.\scripts\publish-desktop.ps1
```

产物在 `dist/`：

- `M-Bot-Setup-0.1.0.exe` — **Inno Setup 安装包**。双击安装到 `%LOCALAPPDATA%\Programs\M Bot`，写入开始菜单，可在「设置 > 应用」里卸载。不弹管理员 UAC。最低系统 Windows 10 1809（10.0.17763）。
- `app/` — 发布目录，可直接运行 `MHarness.Desktop.exe`
- `M-Bot-0.1.0-win-x64.zip` — 绿色版（解压即用；目录内也有 `Install.ps1` / `Uninstall.ps1`）

向别人分发时给 `Setup.exe` 即可。装好后不必再装 JDK、.NET SDK 或 Git。工作区不必是 git 仓库。

只要绿色版、不编安装程序：

```powershell
.\scripts\publish-desktop.ps1 -SkipInstaller
```

不捆绑 JRE（目标机器自己有 JDK 21）：

```powershell
.\scripts\publish-desktop.ps1 -SkipJre
```

## 命令行

```powershell
java -jar agent-cli/target/m-harness-cli.jar ask "这个仓库做什么"
java -jar agent-cli/target/m-harness-cli.jar agent "修复这个 bug"
java -jar agent-cli/target/m-harness-cli.jar agent --dry-run "把登录 bug 修一下"
java -jar agent-cli/target/m-harness-cli.jar agent --yes "运行测试并修失败用例"
java -jar agent-cli/target/m-harness-cli.jar status
java -jar agent-cli/target/m-harness-cli.jar rollback
```

`--workspace` 可指向目标仓库。Ask 只读。Agent 默认会询问写文件和 Shell；`--yes` 自动批准。`--dry-run` 只预览写操作和命令。

退出码：`0` 成功，`1` 失败，`2` 达到步数上限（checkpoint 仍保留），`130` 取消。

## 本机 HTTP API

调试时可单独启动服务：

```powershell
java -jar agent-server/target/m-harness-server.jar --bind 127.0.0.1 --port 0
```

启动后第一行 JSON 为 `port` 和 `token`，之后请求需带 `Authorization: Bearer <token>`。可选 `--token` 指定口令。非本机地址会拒绝启动。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 探活 |
| `POST` | `/v1/run` | 启动任务（`workspace` / `prompt` / `mode` / `dryRun` / `autoApprove`），返回 `runId`；已有任务时 `409` |
| `GET` | `/v1/run/{id}/events` | SSE：`token` / `tool` / `approval` / `done` |
| `POST` | `/v1/run/{id}/approval` | 批准或拒绝挂起的写操作 |
| `POST` | `/v1/run/{id}/cancel` | 取消任务并拒绝待批准请求 |
| `GET` | `/v1/checkpoint` | 查询当前 checkpoint |
| `POST` | `/v1/rollback` | 回滚到最近 checkpoint |
| `GET` | `/v1/settings` | 读取全局设置（Key 只返回是否已设置和后四位） |
| `PUT` | `/v1/settings` | 写入全局 `~/.m-harness/.env` |
| `POST` | `/v1/title` | 根据第一条用户消息生成侧栏标题，返回 `{ "title": "..." }`；不占用正在运行的任务 |

## 工具

| 工具 | 作用 |
| --- | --- |
| `glob` | 按 glob 列文件，最多 200 条；跳过 `.git` / `target` / `node_modules` |
| `grep` | 精确搜索；优先调用本机 `rg`，否则 Java 遍历。最多 50 条 |
| `read_file` | 读文件（可指定行偏移/行数），输出带行号，约 32KB 截断 |
| `search_replace` | 替换文件中恰好出现一次的旧字符串；不唯一时返回最多 5 处行号 |
| `write_file` | **仅新建**；目标已存在则失败，应改用 `search_replace` |
| `run_terminal` | 在工作区根执行一条命令；超时 120 秒会杀掉进程树 |

变更类工具是 `search_replace`、`write_file`、`run_terminal`。Shell 工作目录锁定在工作区根。

## Checkpoint

快照写在 `refs/m-harness/checkpoints/<id>`，不会使用 `git stash`，也不会在启动时 reset 工作区。快照包含当时磁盘上的文件，跳过 `.git` / `target` / `node_modules`。

- 工作区已有 `.git`：ref 写进用户仓库
- 否则：写入 `~/.m-harness/checkpoints/<工作区路径 SHA-256>/` 的 sidecar 裸仓库，用户目录不会出现 `.git`

Ask 和 dry-run 不拍快照。任务成功后删除该 ref；失败时尝试自动回滚；步数上限、取消或手动 `rollback` 时恢复到快照（包括删除其后新建的文件）。

## 许可证

MIT。详见 [LICENSE](LICENSE)。
