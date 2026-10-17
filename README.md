# MCP Android Bridge

本地安卓 App 内置一个 **MCP Server**（Streamable HTTP），任何支持 MCP 的 AI 客户端
（Claude、Trae 等）只要配上地址和端口，就能：

1. 读取 / 写入 GitHub 仓库源码；
2. 触发 GitHub Actions 云端编译；
3. 拉取构建日志、下载 APK 产物到手机；
4. 诊断编译失败原因（缺依赖 / SDK / JDK / 文件），并补齐后重新编译。

编译不在手机上跑，而是交给 GitHub Actions，手机只做「控制面」。

```
AI 客户端  ──(MCP over HTTP + Bearer Token)──▶  安卓 App(MCP Server)  ──▶  GitHub 仓库 / Actions
```

## 目录结构

```
mcp-android-bridge/
├── .github/workflows/build-apk.yml     # 云端编译，产出 app-debug-apk
├── scripts/
│   ├── pack_and_push.sh                # 一键：推源码 → 触发编译 → 抓回 APK → 传 Release
│   └── pack_and_push.ps1               # 同上，Windows PowerShell 版
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/…
        └── java/com/mcpbridge/android/
            ├── BridgeApp.kt            # Application，初始化配置
            ├── Config.kt               # 本地配置（token / 仓库 / 端口）
            ├── MainActivity.kt         # 配置界面 + 启动/停止服务
            ├── McpBridgeService.kt     # 前台服务，常驻托管 MCP Server
            ├── mcp/
            │   ├── McpServer.kt        # Streamable HTTP + JSON-RPC 2.0
            │   ├── ToolRegistry.kt     # 工具注册表 + JSON Schema 构造
            │   └── Tools.kt            # 全部 MCP 工具实现
            ├── github/GitHubClient.kt  # GitHub REST 客户端
            └── build/LogDiagnoser.kt   # 构建日志规则诊断
```

## 快速开始

### 0) 一条命令拿到 APK（推荐）

本工程自带打包脚本，**不需要本机装 Android SDK**，编译交给 GitHub Actions：

```bash
# Linux / macOS / WSL / Termux
export GITHUB_TOKEN=ghp_xxxxxxxx     # PAT，需 repo + workflow 权限
bash scripts/pack_and_push.sh
```

```powershell
# Windows PowerShell
$env:GITHUB_TOKEN = "ghp_xxxxxxxx"
powershell -ExecutionPolicy Bypass -File scripts/pack_and_push.ps1
```

脚本会自动完成：建仓（不存在则创建）→ 推源码 → 触发 Actions 编译 → 等待 → 下载 APK 到 `out/` →
把 APK 作为 GitHub Release 附件传回仓库。结束后打印 APK 路径与 Release 链接。

常用开关（环境变量）：`GITHUB_OWNER` / `GITHUB_REPO` / `BRANCH` / `REPO_PRIVATE` /
`SKIP_WAIT=1`（只推源码）/ `SKIP_RELEASE=1`（只留本地）/ `FORCE_PUSH=1`（覆盖远端历史）。

> 解析 JSON 优先用 `jq`，其次 `python3`，都没有时会退回 `sed`。Termux 上建议 `pkg install jq unzip`。

### 1) 手动上传到 GitHub（不想跑脚本时）

新建空仓库，把本目录内容推上去（`.github/workflows/build-apk.yml` 会自动跑起来并产出 APK）。

> 本仓库已内置 Gradle Wrapper（`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`
> 与 `gradle-wrapper.properties`，锁定 Gradle 8.7），workflow 优先执行 `./gradlew assembleDebug`；
> 若 wrapper 缺失，才退回 `gradle/actions/setup-gradle@v4` + 直接执行 `gradle`，两种都能编出 APK。

### 2) 本地构建（可选）

装了 Android SDK + JDK 17 的机器上：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 3) 安装 App 并启动 MCP 服务

1. 安装 APK；
2. 填写 GitHub `owner` / `repo` / `branch` / PAT / workflow 文件名 / 端口（默认 8388）；
3. 点「启动 MCP 服务」→ 前台通知常驻；
4. 界面会显示形如 `http://192.168.x.x:8388/mcp` 的地址和访问令牌。

PAT 需要 **repo** 与 **workflow** 权限。令牌只存在手机本地的 SharedPreferences，不会上传。

### 4) 把地址接入 AI 客户端

通用 MCP 客户端配置（以 JSON 形式给出）：

```json
{
  "mcpServers": {
    "android-bridge": {
      "url": "http://192.168.1.23:8388/mcp",
      "headers": {
        "Authorization": "Bearer <App 里显示的令牌>"
      }
    }
  }
}
```

手机与电脑需在同一局域网；不在同一网络时，可用 Tailscale / Cloudflare Tunnel 之类把端口安全地暴露出去。

## MCP 工具清单

| 工具 | 作用 |
|---|---|
| `bridge_status` | 查看监听地址、GitHub 配置、最近一次构建 |
| `get_client_config` | 直接返回接入 AI 客户端所需的 JSON 配置（含地址与令牌） |
| `set_github_config` | 设置 owner / repo / branch / token / workflow |
| `bootstrap_repo` | 一步建仓：仓库不存在就创建，并同步默认分支 |
| `ensure_ci_workflow` | 仓库缺编译 workflow 时自动补上（保证能编出 APK） |
| `list_repo_files` | 列出仓库目录内容 |
| `read_repo_file` | 读取单个文件文本 |
| `push_files` | 批量提交文件（上传源码 / 修复缺失文件） |
| `push_local_project` | 直接读取手机本地目录并整目录上传（自动跳过构建产物/依赖目录） |
| `local_roots` | 查看本机允许 AI 操作的目录白名单（App 私有目录） |
| `list_local_dir` | 列出本机目录内容 |
| `read_local_file` | 读取本机文件文本内容 |
| `write_local_file` | 在本机新建/覆盖文件（自动建父目录） |
| `delete_local_path` | 删除本机文件或整个目录（递归） |
| `move_local_path` | 移动 / 重命名本机的文件或目录 |
| `search_local_dir` | 在本机按关键字或正则搜索源码，返回「文件:行号: 内容」 |
| `trigger_build` | 触发 Actions 构建，返回 run_id |
| `wait_for_build` | 阻塞等待构建结束并返回结论与产物 |
| `build_status` | 查询构建状态与失败步骤 |
| `build_logs` | 下载构建日志文本 |
| `list_artifacts` | 列出构建产物 |
| `download_apk` | 下载 APK 到手机存储并返回路径 |
| `diagnose_build_log` | 规则诊断：识别缺依赖 / SDK / JDK / 文件并给修复建议 |
| `run_pipeline` | **一条龙**：建仓 → 推源码 → 自动补 CI → 触发编译 → 等结果 → 下载 APK |

## 完整闭环示例

**一句话版（AI 只需一次调用）：**

```
run_pipeline（local_dir 或 files + repo 名）
  → 自动建仓 → 推源码 → 自动补 CI → 触发编译 → 等结果 → 自动下载 APK
```

**逐步版（便于中途干预 / 单点调试）：**

```
push_files（上传源码）
  → trigger_build（触发编译）
  → build_status（查看结果）
  → 失败则 build_logs / diagnose_build_log（定位缺什么）
  → push_files（补齐 build.gradle / 补文件）
  → trigger_build（重编译）
  → download_apk（拿到 apk）
```

**修复手机本机里的陌生工程：**

```
list_local_dir / search_local_dir（摸清目录、定位关键文件）
  → read_local_file（看懂源码）
  → write_local_file / delete_local_path / move_local_path（补文件 / 删多余文件 / 改名搬目录）
  → push_local_project（整体上传）
  → trigger_build → build_logs → diagnose_build_log（缺什么补什么）
  → download_apk（直到出包）
```

> 本机操作被限制在 App 私有目录内（用 `local_roots` 查看），不会碰到手机其它文件。


## 安全提示

- 默认监听 `0.0.0.0`，**务必保留 Bearer Token 鉴权**，不要在公网裸奔。
- `usesCleartextTraffic=true` 是为了局域网 HTTP，如需公网建议加隧道 / 反向代理并启用 TLS。
- PAT 是高权限凭证，请使用最小权限、可随时吊销的细粒度令牌。
