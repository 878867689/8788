package com.mcpbridge.android.mcp

import android.content.Context
import android.util.Base64
import com.mcpbridge.android.Config
import com.mcpbridge.android.McpBridgeService
import com.mcpbridge.android.build.CiTemplate
import com.mcpbridge.android.build.LogDiagnoser
import com.mcpbridge.android.github.FileEntry
import com.mcpbridge.android.github.GitHubClient
import com.mcpbridge.android.github.GitHubException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

private const val DEFAULT_EXCLUDES = ".git, build, .gradle, .idea, .kotlin, node_modules, .cxx"
private const val MAX_FILE_BYTES = 4L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
private val SKIP_SEGMENTS = setOf("build", ".git", ".gradle", ".idea", ".kotlin", "node_modules", ".cxx", "captures")
private val SKIP_SUFFIXES = listOf(".apk", ".aab", ".keystore", ".jks", ".war")
private val BINARY_SUFFIXES = listOf(
    ".apk", ".aab", ".jar", ".zip", ".png", ".jpg", ".jpeg", ".gif", ".webp",
    ".so", ".aar", ".class", ".dex", ".ttf", ".otf", ".mp3", ".mp4",
    ".keystore", ".jks", ".bin", ".war"
)

/**
 * 注册所有暴露给 AI 客户端的 MCP 工具。
 */
fun buildToolRegistry(context: Context): ToolRegistry {
    val core = BridgeCore(context)
    val tools = mutableListOf<McpTool>()

    // ------------------------------------------------------------ 状态 / 配置

    tools += McpTool(
        name = "bridge_status",
        description = "查看 MCP 桥状态：监听地址、GitHub 配置、最近一次构建。",
        schema = Schemas.obj()
    ) { ToolResult(core.statusText()) }

    tools += McpTool(
        name = "get_client_config",
        description = "返回把这个 MCP 服务接入 AI 客户端所需的 JSON 配置（含地址与令牌）。",
        schema = Schemas.obj()
    ) { ToolResult(core.clientConfigJson()) }

    tools += McpTool(
        name = "set_github_config",
        description = "设置 GitHub owner / repo / branch / PAT / workflow（仅保存在手机本地）。",
        schema = Schemas.obj(
            mapOf(
                "owner" to Schemas.str("GitHub 用户名或组织"),
                "repo" to Schemas.str("仓库名"),
                "branch" to Schemas.str("分支，默认 main"),
                "token" to Schemas.str("GitHub PAT，需要 repo + workflow 权限"),
                "workflow" to Schemas.str("workflow 文件名，默认 build-apk.yml")
            )
        )
    ) { args ->
        args.optString("owner").takeIf { it.isNotBlank() }?.let { Config.owner = it }
        args.optString("repo").takeIf { it.isNotBlank() }?.let { Config.repo = it }
        args.optString("branch").takeIf { it.isNotBlank() }?.let { Config.branch = it }
        args.optString("token").takeIf { it.isNotBlank() }?.let { Config.githubToken = it }
        args.optString("workflow").takeIf { it.isNotBlank() }?.let { Config.workflowFile = it }
        ToolResult("配置已更新：\n${Config.summary()}")
    }

    // ------------------------------------------------------------ 建仓 / 补齐 CI

    tools += McpTool(
        name = "bootstrap_repo",
        description = "一步建仓：仓库不存在就创建，并把默认分支设成当前分支。用于「我不会操作 GitHub」的场景。",
        schema = Schemas.obj(
            mapOf(
                "name" to Schemas.str("仓库名；省略则用已配置的 repo"),
                "private" to Schemas.bool("是否私有仓库", true),
                "description" to Schemas.str("仓库描述")
            )
        )
    ) { args ->
        val isPrivate = if (args.has("private")) args.optBoolean("private", true) else true
        ToolResult(core.ensureRepo(
            name = args.optString("name").ifBlank { null },
            isPrivate = isPrivate,
            description = args.optString("description").ifBlank { null }
        ))
    }

    tools += McpTool(
        name = "ensure_ci_workflow",
        description = "检查仓库是否已有编译 workflow，缺了就自动补上（保证能编出 APK）。",
        schema = Schemas.obj(
            mapOf("branch" to Schemas.str("分支，默认当前分支"))
        )
    ) { args ->
        val branch = args.optString("branch").ifBlank { Config.branch }
        ToolResult(core.ensureWorkflow(branch))
    }

    // ------------------------------------------------------------ 读源码

    tools += McpTool(
        name = "list_repo_files",
        description = "列出仓库目录下的文件；path 留空表示仓库根目录。",
        schema = Schemas.obj(
            mapOf(
                "path" to Schemas.str("目录路径，留空=根目录", ""),
                "ref" to Schemas.str("分支或提交，默认当前分支")
            )
        )
    ) { args ->
        val path = args.optString("path")
        val list = core.github.listContents(path, args.optString("ref").ifBlank { null })
        val sb = StringBuilder("目录 ${path.ifBlank { "/" }} 共 ${list.length()} 项：\n")
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val size = item.optLong("size", 0L)
            sb.append(if (item.optString("type") == "dir") "[dir ] " else "[file] ")
                .append(item.optString("path"))
                .append(if (size > 0) "  ${size}B" else "")
                .append('\n')
        }
        ToolResult(sb.toString())
    }

    tools += McpTool(
        name = "read_repo_file",
        description = "读取仓库中单个文件的文本内容。",
        schema = Schemas.obj(
            mapOf(
                "path" to Schemas.str("文件路径，例如 app/build.gradle.kts"),
                "ref" to Schemas.str("分支或提交，默认当前分支"),
                "max_chars" to Schemas.int("最多返回字符数", 20000)
            ),
            required = listOf("path")
        )
    ) { args ->
        val path = args.optString("path").trim()
        if (path.isBlank()) {
            ToolResult("缺少参数 path", true)
        } else {
            val file = core.github.readFile(path, args.optString("ref").ifBlank { null })
            val content = file.optString("content")
            val max = args.optInt("max_chars", 20000)
            val body = if (content.length > max) {
                content.take(max) + "\n...(已截断，全文共 ${content.length} 字符)"
            } else {
                content
            }
            ToolResult("${file.optString("path")} (${file.optLong("size")}B)\n\n$body")
        }
    }

    // ------------------------------------------------------------ 写源码

    tools += McpTool(
        name = "push_files",
        description = "把一批文件提交到 GitHub 仓库（新建或覆盖）。AI 客户端可直接读取本地工程再把内容传进来。",
        schema = Schemas.obj(
            mapOf(
                "files" to Schemas.fileArray("要写入的文件列表"),
                "message" to Schemas.str("提交信息"),
                "branch" to Schemas.str("目标分支，默认当前分支")
            ),
            required = listOf("files")
        )
    ) { args ->
        val entries = entriesFromJson(args.optJSONArray("files"))
        if (entries.isEmpty()) {
            ToolResult("files 不能为空或缺少有效的 path", true)
        } else {
            val message = args.optString("message").ifBlank { "Update via MCP Android Bridge" }
            val branch = args.optString("branch").ifBlank { Config.branch }
            val result = core.github.pushFiles(entries, message, branch)
            ToolResult(
                "已提交 ${result.optInt("files")} 个文件到 ${result.optString("branch")}\n" +
                    "commit: ${result.optString("commit")}\n" +
                    result.optString("html_url")
            )
        }
    }

    tools += McpTool(
        name = "push_local_project",
        description = "读取手机本地目录里的源码并整体推送到 GitHub（相对路径基于 App 外部文件目录）。",
        schema = Schemas.obj(
            mapOf(
                "local_dir" to Schemas.str("本地目录；相对路径基于 App 外部文件目录，也支持绝对路径"),
                "branch" to Schemas.str("目标分支，默认当前分支"),
                "message" to Schemas.str("提交信息"),
                "excludes" to Schemas.strArray("额外排除的路径子串，例如 [\"secret\",\"docs\"]")
            ),
            required = listOf("local_dir")
        )
    ) { args ->
        val localDir = args.optString("local_dir").trim()
        if (localDir.isBlank()) {
            ToolResult("缺少参数 local_dir", true)
        } else {
            val (entries, skipped) = core.readLocalProject(localDir, parseExcludes(args))
            val message = args.optString("message").ifBlank { "chore: upload project source" }
            val branch = args.optString("branch").ifBlank { Config.branch }
            val result = core.github.pushFiles(entries, message, branch)
            ToolResult(buildString {
                appendLine("已上传 ${result.optInt("files")} 个文件到 ${result.optString("branch")}")
                appendLine("commit: ${result.optString("commit")}")
                appendLine(result.optString("html_url"))
                if (skipped.isNotEmpty()) {
                    appendLine("跳过 ${skipped.size} 个文件（构建产物/依赖目录等）：")
                    skipped.take(10).forEach { appendLine("  - $it") }
                }
            })
        }
    }

    // ------------------------------------------------------------ 本机文件操作

    tools += McpTool(
        name = "local_roots",
        description = "返回本机允许 AI 增删改的目录（App 私有目录），避免误删手机其它文件。",
        schema = Schemas.obj()
    ) { ToolResult(core.localRootsText()) }

    tools += McpTool(
        name = "list_local_dir",
        description = "列出手机本地目录内容（相对路径基于 App 项目目录；也可用绝对路径，但必须位于 App 私有目录内）。",
        schema = Schemas.obj(
            mapOf("path" to Schemas.str("目录路径，留空=项目根目录", ""))
        )
    ) { args ->
        ToolResult(core.listLocal(core.resolveLocalPath(args.optString("path"))))
    }

    tools += McpTool(
        name = "read_local_file",
        description = "读取手机本地文件的文本内容，用于 AI 看懂本机源码后再决定怎么改。",
        schema = Schemas.obj(
            mapOf(
                "path" to Schemas.str("文件路径（相对或绝对）"),
                "max_chars" to Schemas.int("最多返回字符数", 20000)
            ),
            required = listOf("path")
        )
    ) { args ->
        val path = args.optString("path").trim()
        if (path.isBlank()) {
            ToolResult("缺少参数 path", true)
        } else {
            ToolResult(
                core.readLocalFile(core.resolveLocalPath(path), args.optInt("max_chars", 20000))
            )
        }
    }

    tools += McpTool(
        name = "write_local_file",
        description = "在本机新建或覆盖文件（自动创建父目录）。用于 AI 直接修补手机里的源码。",
        schema = Schemas.obj(
            mapOf(
                "path" to Schemas.str("文件路径（相对或绝对）"),
                "content" to Schemas.str("文件文本内容（utf-8）"),
                "base64" to Schemas.str("可选：base64 内容，用于二进制文件")
            ),
            required = listOf("path")
        )
    ) { args ->
        val path = args.optString("path").trim()
        if (path.isBlank()) {
            ToolResult("缺少参数 path", true)
        } else {
            val b64 = args.optString("base64").takeIf { it.isNotBlank() }
            val bytes = if (b64 != null) {
                Base64.decode(b64, Base64.DEFAULT)
            } else {
                args.optString("content").toByteArray(Charsets.UTF_8)
            }
            ToolResult(core.writeLocalFile(core.resolveLocalPath(path), bytes))
        }
    }

    tools += McpTool(
        name = "delete_local_path",
        description = "删除本机的文件或整个目录（递归删除）。仅允许操作 App 私有目录内。",
        schema = Schemas.obj(
            mapOf("path" to Schemas.str("要删除的文件或目录路径")),
            required = listOf("path")
        )
    ) { args ->
        val path = args.optString("path").trim()
        if (path.isBlank()) {
            ToolResult("缺少参数 path", true)
        } else {
            ToolResult(core.deleteLocal(core.resolveLocalPath(path)))
        }
    }

    tools += McpTool(
        name = "move_local_path",
        description = "移动或重命名本机的文件/目录。仅允许操作 App 私有目录内。",
        schema = Schemas.obj(
            mapOf(
                "from" to Schemas.str("源路径"),
                "to" to Schemas.str("目标路径"),
                "overwrite" to Schemas.bool("目标已存在时是否覆盖", false)
            ),
            required = listOf("from", "to")
        )
    ) { args ->
        val from = args.optString("from").trim()
        val to = args.optString("to").trim()
        if (from.isBlank() || to.isBlank()) {
            ToolResult("缺少参数 from / to", true)
        } else {
            ToolResult(
                core.moveLocal(
                    core.resolveLocalPath(from),
                    core.resolveLocalPath(to),
                    args.optBoolean("overwrite", false)
                )
            )
        }
    }

    tools += McpTool(
        name = "search_local_dir",
        description = "在本机目录里按关键字（或正则）搜索源码，返回「文件:行号: 内容」。修复陌生工程时用来快速定位。",
        schema = Schemas.obj(
            mapOf(
                "path" to Schemas.str("要搜索的文件或目录，留空=项目根目录", ""),
                "query" to Schemas.str("关键字；regex=true 时为正则表达式"),
                "regex" to Schemas.bool("是否按正则匹配", false),
                "max_results" to Schemas.int("最多命中条数", 100)
            ),
            required = listOf("query")
        )
    ) { args ->
        val query = args.optString("query")
        if (query.isBlank()) {
            ToolResult("缺少参数 query", true)
        } else {
            ToolResult(
                core.searchLocal(
                    core.resolveLocalPath(args.optString("path")),
                    query,
                    args.optInt("max_results", 100),
                    args.optBoolean("regex", false)
                )
            )
        }
    }

    // ------------------------------------------------------------ 构建

    tools += McpTool(
        name = "trigger_build",
        description = "触发 GitHub Actions 编译（workflow_dispatch），立即返回 run_id，不等结果。",
        schema = Schemas.obj(
            mapOf(
                "workflow" to Schemas.str("workflow 文件名，默认取配置值"),
                "ref" to Schemas.str("分支，默认当前分支")
            )
        )
    ) { args ->
        val workflow = args.optString("workflow").ifBlank { Config.workflowFile }
        val ref = args.optString("ref").ifBlank { Config.branch }
        core.github.triggerWorkflow(workflow, ref)
        Thread.sleep(4000)
        val run = core.latestRun()
        if (run == null) {
            ToolResult("已触发 $workflow @ $ref，但暂时查不到 run，请稍后用 build_status 查询。")
        } else {
            Config.lastRunId = run.optLong("id")
            ToolResult(
                "已触发 $workflow @ $ref\n" +
                    "run_id=${run.optLong("id")}\n" +
                    "status=${run.optString("status")}\n" +
                    run.optString("html_url")
            )
        }
    }

    tools += McpTool(
        name = "wait_for_build",
        description = "阻塞等待某次构建结束（默认等 10 分钟），返回最终结论与产物列表。",
        schema = Schemas.obj(
            mapOf(
                "run_id" to Schemas.int("运行 ID，省略取最新"),
                "timeout_seconds" to Schemas.int("最长等待秒数", 600)
            )
        )
    ) { args ->
        val runId = if (args.optLong("run_id", 0L) > 0L) {
            args.optLong("run_id", 0L)
        } else {
            core.latestRun()?.optLong("id") ?: 0L
        }
        if (runId <= 0L) {
            ToolResult("没有找到构建记录。", true)
        } else {
            core.waitForRun(runId, args.optInt("timeout_seconds", 600))
            ToolResult(core.runSummary(runId))
        }
    }

    tools += McpTool(
        name = "build_status",
        description = "查询构建状态与各步骤结果；省略 run_id 时取最新一次构建。",
        schema = Schemas.obj(
            mapOf("run_id" to Schemas.int("运行 ID，省略取最新"))
        )
    ) { args ->
        val run = core.resolveRun(args)
        if (run == null) {
            ToolResult("没有找到构建记录。", true)
        } else {
            ToolResult(core.runSummary(run.optLong("id")))
        }
    }

    tools += McpTool(
        name = "build_logs",
        description = "下载并返回构建日志文本，用于定位编译错误。",
        schema = Schemas.obj(
            mapOf(
                "run_id" to Schemas.int("运行 ID，省略取最新"),
                "max_chars" to Schemas.int("最多返回字符数", 20000),
                "tail" to Schemas.bool("true=返回末尾（错误通常在末尾）", true)
            )
        )
    ) { args ->
        val run = core.resolveRun(args)
        if (run == null) {
            ToolResult("没有找到构建记录。", true)
        } else {
            val id = run.optLong("id")
            val log = core.github.downloadRunLogs(id)
            val max = args.optInt("max_chars", 20000)
            val body = if (log.length > max) {
                if (args.optBoolean("tail", true)) {
                    "...(已省略前 ${log.length - max} 字符)\n" + log.takeLast(max)
                } else {
                    log.take(max) + "\n...(已截断)"
                }
            } else {
                log
            }
            ToolResult("run_id=$id  ${run.optString("status")}/${run.optString("conclusion")}\n\n$body")
        }
    }

    tools += McpTool(
        name = "list_artifacts",
        description = "列出某次构建的产物（artifact），例如 APK。",
        schema = Schemas.obj(
            mapOf("run_id" to Schemas.int("运行 ID，省略取最新"))
        )
    ) { args ->
        val run = core.resolveRun(args)
        if (run == null) {
            ToolResult("没有找到构建记录。", true)
        } else {
            val artifacts = core.github.listArtifacts(run.optLong("id"))
            if (artifacts.length() == 0) {
                ToolResult("该 run 暂无产物（可能仍在构建，或产物已被清理）。")
            } else {
                val sb = StringBuilder("run_id=${run.optLong("id")} 共 ${artifacts.length()} 个产物：\n")
                for (i in 0 until artifacts.length()) {
                    val a = artifacts.getJSONObject(i)
                    sb.appendLine(
                        "id=${a.optLong("id")}  name=${a.optString("name")}  " +
                            "size=${a.optLong("size_in_bytes")}B  expired=${a.optBoolean("expired")}"
                    )
                }
                ToolResult(sb.toString())
            }
        }
    }

    tools += McpTool(
        name = "download_apk",
        description = "下载构建产物里的 APK 到手机存储，返回本地路径。",
        schema = Schemas.obj(
            mapOf(
                "run_id" to Schemas.int("运行 ID，省略取最新"),
                "artifact_id" to Schemas.int("指定产物 ID；省略则自动选名字含 apk 的产物")
            )
        )
    ) { args ->
        val run = core.resolveRun(args)
        if (run == null) {
            ToolResult("没有找到构建记录。", true)
        } else {
            ToolResult(core.saveApk(run.optLong("id"), args.optLong("artifact_id", 0L)))
        }
    }

    tools += McpTool(
        name = "diagnose_build_log",
        description = "诊断构建失败原因：识别缺依赖 / SDK / JDK / 文件等问题并给出修复建议。",
        schema = Schemas.obj(
            mapOf(
                "run_id" to Schemas.int("运行 ID，省略取最新（会自动拉取日志）"),
                "log" to Schemas.str("也可以直接传入日志文本"),
                "max_chars" to Schemas.int("拉取日志时的字符上限", 60000)
            )
        )
    ) { args ->
        val direct = args.optString("log")
        val log = if (direct.isNotBlank()) {
            direct
        } else {
            val run = core.resolveRun(args)
            if (run == null) {
                ""
            } else {
                val full = core.github.downloadRunLogs(run.optLong("id"))
                val max = args.optInt("max_chars", 60000)
                if (full.length > max) full.takeLast(max) else full
            }
        }
        if (log.isBlank()) {
            ToolResult("没有可用的日志（run 未结束、产物已过期，或未提供 log）。", true)
        } else {
            ToolResult(LogDiagnoser.diagnose(log).toString(2))
        }
    }

    // ------------------------------------------------------------ 一条龙

    tools += McpTool(
        name = "run_pipeline",
        description = "一条龙：建仓 → 推源码 → 自动补 CI → 触发编译 → 等结果 → 下载 APK 到手机。",
        schema = Schemas.obj(
            mapOf(
                "name" to Schemas.str("仓库名；省略则用已配置的 repo"),
                "private" to Schemas.bool("是否私有仓库", true),
                "files" to Schemas.fileArray("要上传的文件（AI 从本地工程读取后传进来）"),
                "local_dir" to Schemas.str("或者：手机本地目录，直接整目录上传"),
                "excludes" to Schemas.strArray("push local_dir 时额外排除的路径子串"),
                "message" to Schemas.str("提交信息"),
                "branch" to Schemas.str("分支，默认当前分支"),
                "workflow" to Schemas.str("workflow 文件名，默认取配置值"),
                "wait_seconds" to Schemas.int("最长等待构建完成的秒数", 600),
                "download_apk" to Schemas.bool("构建成功后自动把 APK 下载到手机", true)
            )
        )
    ) { args ->
        val report = StringBuilder()

        // 1) 建仓（不存在则创建），并把默认分支同步到配置
        report.appendLine(
            core.ensureRepo(
                name = args.optString("name").ifBlank { null },
                isPrivate = if (args.has("private")) args.optBoolean("private", true) else true,
                description = args.optString("description").ifBlank { null }
            )
        )

        val branch = args.optString("branch").ifBlank { Config.branch }

        // 2) 推源码
        val entries = ArrayList<FileEntry>()
        entriesFromJson(args.optJSONArray("files")).let { entries.addAll(it) }

        val localDir = args.optString("local_dir").trim()
        if (localDir.isNotBlank()) {
            val (fromDir, skipped) = core.readLocalProject(localDir, parseExcludes(args))
            entries.addAll(fromDir)
            if (skipped.isNotEmpty()) {
                report.appendLine("已跳过 ${skipped.size} 个文件（构建产物/依赖目录等）")
            }
        }

        if (entries.isEmpty()) {
            report.appendLine("未提供 files / local_dir，跳过推送（沿用仓库现有源码）")
        } else {
            val message = args.optString("message").ifBlank { "chore: sync project source" }
            val pushed = core.github.pushFiles(entries, message, branch)
            report.appendLine(
                "已推送 ${pushed.optInt("files")} 个文件到 ${pushed.optString("branch")}，" +
                    "commit=${pushed.optString("commit").take(8)}"
            )
        }

        // 3) 补齐 CI 配置
        report.appendLine(core.ensureWorkflow(branch))

        // 4) 触发并等待
        val workflow = args.optString("workflow").ifBlank { Config.workflowFile }
        val run = core.triggerAndWait(workflow, branch, 90, args.optInt("wait_seconds", 600))
        val runId = run.optLong("id")
        report.appendLine()
        report.append(core.runSummary(runId))

        // 5) 取产物
        val conclusion = run.optString("conclusion")
        when {
            conclusion == "success" && args.optBoolean("download_apk", true) -> {
                report.appendLine()
                report.appendLine(core.saveApk(runId, 0L))
            }

            run.optString("status") != "completed" -> {
                report.appendLine()
                report.appendLine("构建仍在进行中，稍后再调用 wait_for_build / build_status / download_apk。")
            }

            else -> {
                report.appendLine()
                report.appendLine("构建未成功。下一步建议：build_logs → diagnose_build_log → push_files 补全 → 再次 run_pipeline。")
            }
        }

        ToolResult(report.toString(), isError = conclusion == "failure")
    }

    return ToolRegistry(tools)
}

// ------------------------------------------------------------------ helpers

private fun entriesFromJson(arr: JSONArray?): List<FileEntry> {
    if (arr == null || arr.length() == 0) return emptyList()
    val out = ArrayList<FileEntry>()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val path = item.optString("path").trim().trim('/')
        if (path.isBlank()) continue
        val base64 = item.optString("base64").takeIf { it.isNotBlank() }
            ?: GitHubClient.base64Of(item.optString("content"))
        out.add(FileEntry(path, base64))
    }
    return out
}

private fun parseExcludes(args: JSONObject): List<String> {
    val arr = args.optJSONArray("excludes")
    if (arr == null || arr.length() == 0) {
        return DEFAULT_EXCLUDES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val out = ArrayList<String>()
    for (i in 0 until arr.length()) {
        val v = arr.optString(i).trim()
        if (v.isNotBlank()) out.add(v)
    }
    return out
}

private fun shouldSkip(rel: String, excludes: List<String>): Boolean {
    val segments = rel.split('/')
    if (segments.any { it in SKIP_SEGMENTS }) return true
    val name = segments.last()
    if (name == "local.properties" || name == ".DS_Store") return true
    if (SKIP_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) return true
    return excludes.any { rel.contains(it) }
}

/**
 * 工具背后的真实逻辑，供多个工具（尤其 run_pipeline）复用。
 */
class BridgeCore(private val context: Context) {

    val github = GitHubClient()

    fun statusText(): String {
        val sb = StringBuilder()
        sb.appendLine("MCP 地址: http://${McpBridgeService.getLocalIpv4()}:${Config.port}/mcp")
        sb.appendLine("服务运行中: ${McpBridgeService.running}")
        McpBridgeService.lastError?.let { sb.appendLine("错误: $it") }
        sb.appendLine("APK 保存目录: ${apkDir().absolutePath}")
        sb.appendLine("本地上传目录: ${localUploadDir().absolutePath}")
        sb.appendLine()
        sb.append(Config.summary())
        return sb.toString()
    }

    fun clientConfigJson(): String {
        val ip = McpBridgeService.getLocalIpv4()
        return """
{
  "mcpServers": {
    "android-bridge": {
      "url": "http://$ip:${Config.port}/mcp",
      "headers": { "Authorization": "Bearer ${Config.ensureAuthToken()}" }
    }
  }
}
""".trimIndent()
    }

    fun apkDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "artifacts").apply { mkdirs() }

    fun localUploadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "projects").apply { mkdirs() }

    fun resolveProjectDir(dir: String): File {
        val direct = File(dir)
        return if (direct.isAbsolute) direct else File(localUploadDir(), dir)
    }

    // ------------------------------------------------------ 本机文件操作

    /** 允许 AI 操作的根目录白名单：App 的私有目录，避免误删手机其它文件。 */
    fun localRoots(): List<File> = listOfNotNull(
        context.getExternalFilesDir(null),
        context.filesDir
    ).map { it.canonicalFile }

    fun localRootsText(): String = buildString {
        appendLine("AI 只能操作以下目录内（App 私有目录）：")
        localRoots().forEach { appendLine("  - ${it.absolutePath}") }
        appendLine("项目根目录：${localUploadDir().absolutePath}")
        appendLine("产物目录：${apkDir().absolutePath}")
    }

    /** 把相对/绝对路径解析成真实路径，并强制落在白名单目录内。 */
    fun resolveLocalPath(path: String): File {
        val cleaned = path.trim().ifBlank { "." }
        val raw = File(cleaned)
        val target = if (raw.isAbsolute) raw else File(localUploadDir(), cleaned)
        val canon = target.canonicalFile
        val allowed = localRoots().any {
            canon.path == it.path || canon.path.startsWith(it.path + File.separator)
        }
        if (!allowed) {
            throw IllegalStateException(
                "出于安全考虑，只能操作 App 私有目录：" +
                    localRoots().joinToString("、") { it.absolutePath }
            )
        }
        return canon
    }

    /** 受保护目录：白名单根本身与项目根目录，不允许被删除/覆盖。 */
    private fun isProtectedRoot(f: File): Boolean {
        val c = f.canonicalFile.path
        return (localRoots() + localUploadDir()).any { it.canonicalFile.path == c }
    }

    /** 展示用路径：项目目录内的显示成 projects/xxx，方便 AI 理解。 */
    fun displayPath(f: File): String {
        val base = localUploadDir().canonicalFile
        val canon = f.canonicalFile
        return if (canon.path == base.path || canon.path.startsWith(base.path + File.separator)) {
            "projects/" + canon.relativeTo(base).invariantSeparatorsPath.ifEmpty { "." }
        } else {
            canon.absolutePath
        }
    }

    fun listLocal(dir: File): String {
        if (!dir.exists()) throw IllegalStateException("目录不存在：${displayPath(dir)}")
        if (!dir.isDirectory) throw IllegalStateException("不是目录：${displayPath(dir)}")
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: emptyList()
        val sb = StringBuilder("目录 ${displayPath(dir)} 共 ${items.size} 项：\n")
        for (f in items) {
            sb.append(if (f.isDirectory) "[dir ] " else "[file] ").append(displayPath(f))
            if (f.isFile) sb.append("  ${f.length()}B")
            sb.append('\n')
        }
        return sb.toString()
    }

    fun readLocalFile(file: File, maxChars: Int): String {
        if (!file.exists()) throw IllegalStateException("文件不存在：${displayPath(file)}")
        if (!file.isFile) throw IllegalStateException("不是文件：${displayPath(file)}")
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalStateException("文件过大（${file.length()}B），超过 ${MAX_FILE_BYTES}B 上限")
        }
        val text = file.readText(Charsets.UTF_8)
        val body = if (text.length > maxChars) {
            text.take(maxChars) + "\n...(已截断，全文共 ${text.length} 字符)"
        } else {
            text
        }
        return "${displayPath(file)}（${file.length()}B）\n\n$body"
    }

    fun writeLocalFile(file: File, bytes: ByteArray): String {
        if (isProtectedRoot(file)) throw IllegalStateException("不能把受保护目录当文件写入：${displayPath(file)}")
        if (bytes.size > MAX_FILE_BYTES) {
            throw IllegalStateException("内容过大（${bytes.size}B），超过 ${MAX_FILE_BYTES}B 上限")
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return "已写入：${displayPath(file)}（${bytes.size}B）"
    }

    fun deleteLocal(target: File): String {
        if (isProtectedRoot(target)) throw IllegalStateException("拒绝删除受保护目录：${displayPath(target)}")
        if (!target.exists()) throw IllegalStateException("路径不存在：${displayPath(target)}")
        val wasDir = target.isDirectory
        val fileCount = if (wasDir) target.walkBottomUp().count { it.isFile } else 0
        val ok = if (wasDir) target.deleteRecursively() else target.delete()
        if (!ok || target.exists()) throw IllegalStateException("删除失败（文件可能被占用）：${displayPath(target)}")
        return if (wasDir) {
            "已删除目录：${displayPath(target)}（含 $fileCount 个文件）"
        } else {
            "已删除文件：${displayPath(target)}"
        }
    }

    fun moveLocal(from: File, to: File, overwrite: Boolean): String {
        if (isProtectedRoot(from)) throw IllegalStateException("拒绝移动受保护目录：${displayPath(from)}")
        if (!from.exists()) throw IllegalStateException("源路径不存在：${displayPath(from)}")
        if (from.canonicalFile == to.canonicalFile) {
            throw IllegalStateException("源与目标相同：${displayPath(from)}")
        }
        if (to.exists()) {
            if (!overwrite) {
                throw IllegalStateException("目标已存在（可传 overwrite=true 覆盖）：${displayPath(to)}")
            }
            if (isProtectedRoot(to)) throw IllegalStateException("拒绝覆盖受保护目录：${displayPath(to)}")
            if (to.isDirectory) to.deleteRecursively() else to.delete()
        }
        to.parentFile?.mkdirs()

        var ok = from.renameTo(to)
        if (!ok) {
            // 跨文件系统：复制后删除
            if (from.isDirectory) from.copyRecursively(to, overwrite = true) else to.writeBytes(from.readBytes())
            from.deleteRecursively()
            ok = to.exists()
        }
        if (!ok || !to.exists()) throw IllegalStateException("移动失败：${displayPath(from)} → ${displayPath(to)}")
        return "已移动：${displayPath(from)} → ${displayPath(to)}"
    }

    fun searchLocal(root: File, query: String, maxResults: Int, useRegex: Boolean): String {
        if (!root.exists()) throw IllegalStateException("路径不存在：${displayPath(root)}")
        val matches: (String) -> Boolean = if (useRegex) {
            val re = Regex(query)
            { line -> re.containsMatchIn(line) }
        } else {
            { line -> line.contains(query) }
        }

        val base = if (root.isDirectory) root else root.parentFile ?: root
        val stream = if (root.isDirectory) root.walkTopDown().filter { it.isFile } else sequenceOf(root)

        val hits = StringBuilder()
        var hitCount = 0
        var scanned = 0
        for (f in stream) {
            if (hitCount >= maxResults) break
            val rel = runCatching { f.relativeTo(base).invariantSeparatorsPath }.getOrDefault(f.name)
            if (shouldSkip(rel, emptyList())) continue
            if (f.name.lowercase().let { n -> BINARY_SUFFIXES.any { n.endsWith(it) } }) continue
            if (f.length() > MAX_FILE_BYTES) continue
            scanned++
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            for ((i, line) in lines.withIndex()) {
                if (hitCount >= maxResults) break
                if (matches(line)) {
                    hitCount++
                    hits.appendLine("${displayPath(f)}:${i + 1}: ${line.trim().take(200)}")
                }
            }
        }

        return buildString {
            if (hitCount == 0) {
                append("在 ").append(displayPath(root)).append(" 搜索「").append(query)
                append("」：没有命中（已扫描 ").append(scanned).append(" 个文本文件）。")
            } else {
                append("在 ").append(displayPath(root)).append(" 搜索「").append(query)
                append("」，命中 ").append(hitCount).append(" 条（已扫描 ").append(scanned).append(" 个文本文件")
                if (hitCount >= maxResults) append("，已达上限 ").append(maxResults)
                append("）：\n")
                append(hits)
            }
        }
    }

    fun readLocalProject(dir: String, excludes: List<String>): Pair<List<FileEntry>, List<String>> {
        val root = resolveProjectDir(dir)
        if (!root.exists() || !root.isDirectory) {
            throw IllegalStateException("目录不存在或不是目录：${root.absolutePath}")
        }

        val entries = ArrayList<FileEntry>()
        val skipped = ArrayList<String>()
        var total = 0L

        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (shouldSkip(rel, excludes)) {
                skipped.add(rel)
                return@forEach
            }
            val length = file.length()
            if (length > MAX_FILE_BYTES) {
                skipped.add("$rel（${length}B 超过单文件上限）")
                return@forEach
            }
            if (total + length > MAX_TOTAL_BYTES) {
                skipped.add("$rel（超过总量上限）")
                return@forEach
            }
            total += length
            entries.add(FileEntry(rel, GitHubClient.base64OfBytes(file.readBytes())))
        }

        if (entries.isEmpty()) {
            throw IllegalStateException("目录 ${root.absolutePath} 下没有可上传的文件")
        }
        return entries to skipped
    }

    /** 仓库不存在则创建，并把 GitHub 的默认分支同步到本地配置。 */
    fun ensureRepo(name: String?, isPrivate: Boolean, description: String?): String {
        if (Config.githubToken.isBlank()) {
            throw IllegalStateException("缺少 GitHub token，请先调用 set_github_config")
        }
        if (Config.owner.isBlank()) {
            throw IllegalStateException("缺少 owner，请先调用 set_github_config")
        }
        val target = name?.takeIf { it.isNotBlank() } ?: Config.repo
        if (target.isBlank()) {
            throw IllegalStateException("未指定仓库名（name 与 repo 都为空）")
        }
        Config.repo = target

        val existing = try {
            github.getRepo()
        } catch (e: GitHubException) {
            if (e.statusCode == 404) null else throw e
        }

        val repo = existing ?: github.createRepo(
            target,
            isPrivate,
            description ?: "Created by MCP Android Bridge"
        )

        repo.optString("name").takeIf { it.isNotBlank() }?.let { Config.repo = it }
        repo.optJSONObject("owner")?.optString("login")?.takeIf { it.isNotBlank() }
            ?.let { Config.owner = it }
        repo.optString("default_branch").takeIf { it.isNotBlank() }?.let { Config.branch = it }

        val action = if (existing != null) "仓库已存在" else "仓库已创建"
        return "$action：${repo.optString("html_url")}\n默认分支：${Config.branch}"
    }

    /** 仓库里没有 workflow 就补一个，保证一定能编出 APK。 */
    fun ensureWorkflow(branch: String): String {
        val path = CiTemplate.ANDROID_WORKFLOW_PATH
        val present = try {
            github.readFile(path, branch).optString("content").isNotBlank()
        } catch (_: GitHubException) {
            false
        }
        if (present) {
            return "CI 配置已存在：$path"
        }
        github.pushFiles(
            listOf(FileEntry(path, GitHubClient.base64Of(CiTemplate.ANDROID_APK_WORKFLOW))),
            "ci: add Android APK build workflow",
            branch
        )
        return "CI 配置已补齐：$path（已写入 $branch）"
    }

    fun latestRun(): JSONObject? {
        val runs = github.listRuns(5)
        return if (runs.length() > 0) runs.getJSONObject(0) else null
    }

    fun resolveRun(args: JSONObject): JSONObject? {
        val explicit = args.optLong("run_id", 0L)
        if (explicit > 0L) {
            val run = runCatching { github.getRun(explicit) }.getOrNull() ?: return null
            Config.lastRunId = explicit
            return run
        }
        val run = latestRun() ?: return null
        Config.lastRunId = run.optLong("id")
        return run
    }

    /** 触发编译并等到出现新的 run（最多等 dispatchTimeoutSec 秒）。 */
    fun triggerAndWait(workflow: String, ref: String, dispatchTimeoutSec: Int, waitTimeoutSec: Int): JSONObject {
        val before = latestRun()?.optLong("id") ?: 0L
        github.triggerWorkflow(workflow, ref)

        var run: JSONObject? = null
        val deadline = System.currentTimeMillis() + dispatchTimeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val candidate = latestRun()
            if (candidate != null && candidate.optLong("id") != before) {
                run = candidate
                break
            }
        }
        if (run == null) {
            run = latestRun() ?: throw IllegalStateException("已触发构建，但查不到 run，请稍后用 build_status 查询。")
        }

        Config.lastRunId = run.optLong("id")
        return waitForRun(run.optLong("id"), waitTimeoutSec)
    }

    /** 轮询直到 run 结束或超时。 */
    fun waitForRun(runId: Long, timeoutSec: Int): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var run = github.getRun(runId)
        while (run.optString("status") != "completed" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5000)
            run = github.getRun(runId)
        }
        Config.lastRunId = runId
        return run
    }

    fun runSummary(runId: Long): String {
        val run = github.getRun(runId)
        val sb = StringBuilder()
        sb.appendLine("run_id=$runId")
        sb.appendLine("status=${run.optString("status")}  conclusion=${run.optString("conclusion")}")
        sb.appendLine("branch=${run.optString("head_branch")}  sha=${run.optString("head_sha").take(8)}")
        sb.appendLine("url=${run.optString("html_url")}")

        runCatching {
            val jobs = github.getRunJobs(runId)
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                sb.appendLine("job: ${job.optString("name")}  ${job.optString("status")}/${job.optString("conclusion")}")
                val steps = job.optJSONArray("steps")
                if (steps != null) {
                    for (k in 0 until steps.length()) {
                        val step = steps.getJSONObject(k)
                        if (step.optString("conclusion") == "failure") {
                            sb.appendLine("   x 失败步骤: ${step.optString("name")}")
                        }
                    }
                }
            }
        }

        val artifacts = runCatching { github.listArtifacts(runId) }.getOrNull()
        if (artifacts != null) {
            if (artifacts.length() == 0) {
                sb.appendLine("产物: 无")
            } else {
                sb.appendLine("产物:")
                for (i in 0 until artifacts.length()) {
                    val a = artifacts.getJSONObject(i)
                    sb.appendLine(
                        "  id=${a.optLong("id")}  name=${a.optString("name")}  " +
                            "size=${a.optLong("size_in_bytes")}B"
                    )
                }
            }
        }
        return sb.toString()
    }

    /** 下载 APK 到手机存储，返回可读的结果说明。 */
    fun saveApk(runId: Long, artifactId: Long): String {
        val artifacts = github.listArtifacts(runId)
        val target = pickArtifact(artifacts, artifactId)
            ?: return "该 run 没有可用产物（可能仍在构建，或产物已被清理）。先调用 list_artifacts 看看。"
        val bytes = github.downloadArtifactZip(target.optLong("id"))
        val apk = extractApk(bytes, target.optString("name"))
        return "APK 已保存：${apk.absolutePath}\n来源产物：${target.optString("name")}（${bytes.size}B）"
    }

    private fun extractApk(zipBytes: ByteArray, artifactName: String): File {
        var found: Pair<String, ByteArray>? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    found = entry.name.substringAfterLast('/') to zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        val (name, data) = found ?: throw IllegalStateException("产物 $artifactName 里没有 .apk 文件")
        val out = File(apkDir(), name)
        out.writeBytes(data)
        return out
    }

    private fun pickArtifact(artifacts: JSONArray, wantedId: Long): JSONObject? {
        if (artifacts.length() == 0) return null
        if (wantedId > 0L) {
            for (i in 0 until artifacts.length()) {
                val a = artifacts.getJSONObject(i)
                if (a.optLong("id") == wantedId) return a
            }
        }
        for (i in 0 until artifacts.length()) {
            val a = artifacts.getJSONObject(i)
            if (a.optString("name").contains("apk", ignoreCase = true)) return a
        }
        return artifacts.getJSONObject(0)
    }
}
