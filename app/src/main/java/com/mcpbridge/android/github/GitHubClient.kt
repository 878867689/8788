package com.mcpbridge.android.github

import android.util.Base64
import com.mcpbridge.android.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GitHubException(message: String, val statusCode: Int = 0) : IOException(message)

data class FileEntry(val path: String, val contentBase64: String)

/**
 * 极简 GitHub REST 客户端，覆盖本桥需要的全部操作。
 */
class GitHubClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------- helpers

    private fun enc(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** 按段编码仓库路径，保留 "/" 作为分隔符。 */
    private fun encPath(path: String): String =
        path.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { enc(it) }

    private fun api(path: String, method: String = "GET", body: RequestBody? = null): Request {
        val builder = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "McpAndroidBridge/1.0")

        val token = Config.githubToken
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        return when (method) {
            "GET" -> builder.get().build()
            "DELETE" -> builder.delete().build()
            else -> builder.method(method, body ?: "".toRequestBody(jsonMedia)).build()
        }
    }

    private fun execute(request: Request): Response {
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val raw = try {
                response.body?.string().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val code = response.code
            response.close()
            throw GitHubException("GitHub API $code: ${extractMessage(raw)}", code)
        }
        return response
    }

    private fun extractMessage(raw: String): String {
        if (raw.isBlank()) return "empty response"
        return try {
            val obj = JSONObject(raw)
            val message = obj.optString("message", raw.take(300))
            val errors = obj.optJSONArray("errors")
            if (errors != null) "$message | ${errors.toString().take(300)}" else message
        } catch (_: Exception) {
            raw.take(300)
        }
    }

    private fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val request = api(path, method, body?.toString()?.toRequestBody(jsonMedia))
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun requireRepo() {
        if (!Config.isGithubConfigured()) {
            throw GitHubException("GitHub 未配置：请先设置 owner / repo / token")
        }
    }

    private fun reposPath(): String = "/repos/${enc(Config.owner)}/${enc(Config.repo)}"

    // ---------------------------------------------------------------- repo

    fun createRepo(name: String, isPrivate: Boolean, description: String): JSONObject {
        if (Config.githubToken.isBlank()) {
            throw GitHubException("缺少 GitHub token")
        }
        val body = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
            .put("description", description)
            .put("auto_init", true)
        return json("/user/repos", "POST", body)
    }

    fun getRepo(): JSONObject {
        requireRepo()
        return json(reposPath())
    }

    fun listContents(path: String, ref: String?): JSONArray {
        requireRepo()
        val encoded = encPath(path)
        val suffix = if (encoded.isBlank()) "" else "/$encoded"
        val query = if (ref.isNullOrBlank()) "" else "?ref=${enc(ref)}"
        val request = api("${reposPath()}/contents$suffix$query")
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        }
    }

    fun readFile(path: String, ref: String?): JSONObject {
        requireRepo()
        val query = if (ref.isNullOrBlank()) "" else "?ref=${enc(ref)}"
        val obj = json("${reposPath()}/contents/${encPath(path)}$query")
        val encoded = obj.optString("content", "")
        val text = if (encoded.isBlank()) {
            ""
        } else {
            runCatching {
                String(Base64.decode(encoded.replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
        }
        return JSONObject()
            .put("path", obj.optString("path", path))
            .put("sha", obj.optString("sha", ""))
            .put("size", obj.optLong("size", 0L))
            .put("encoding", "utf-8")
            .put("content", text)
    }

    // ---------------------------------------------------------------- push

    /**
     * 把一组文件一次性提交到指定分支。
     * 空仓库会先用 Contents API 建立首个提交，再用 Git Data API 合并提交其余文件。
     */
    fun pushFiles(files: List<FileEntry>, message: String, branch: String): JSONObject {
        requireRepo()
        if (files.isEmpty()) throw GitHubException("files 不能为空")

        var baseSha = resolveRef(branch)
        val remaining: List<FileEntry>

        if (baseSha == null) {
            // 仓库为空或分支不存在：先用 Contents API 建首个提交（同时自动建分支）
            putContents(files.first().path, files.first().contentBase64, message, branch, sha = null)
            baseSha = resolveRef(branch)
                ?: throw GitHubException("初始提交已创建，但无法解析分支 $branch")
            if (files.size == 1) {
                return JSONObject()
                    .put("branch", branch)
                    .put("commit", baseSha)
                    .put("files", 1)
            }
            remaining = files.drop(1)
        } else {
            remaining = files
        }

        val baseCommit = json("${reposPath()}/git/commits/$baseSha")
        val baseTree = baseCommit.optJSONObject("tree")?.optString("sha")
            ?: throw GitHubException("无法读取 base tree")

        val treeItems = JSONArray()
        for (file in remaining) {
            val blob = json(
                "${reposPath()}/git/blobs",
                "POST",
                JSONObject()
                    .put("content", file.contentBase64)
                    .put("encoding", "base64")
            )
            treeItems.put(
                JSONObject()
                    .put("path", file.path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.optString("sha"))
            )
        }

        val tree = json(
            "${reposPath()}/git/trees",
            "POST",
            JSONObject().put("base_tree", baseTree).put("tree", treeItems)
        )

        val commit = json(
            "${reposPath()}/git/commits",
            "POST",
            JSONObject()
                .put("message", message)
                .put("tree", tree.optString("sha"))
                .put("parents", JSONArray().put(baseSha))
        )

        val newSha = commit.optString("sha")
        json(
            "${reposPath()}/git/refs/heads/${encPath(branch)}",
            "PATCH",
            JSONObject().put("sha", newSha).put("force", false)
        )

        return JSONObject()
            .put("branch", branch)
            .put("commit", newSha)
            .put("files", remaining.size)
            .put("html_url", "https://github.com/${Config.owner}/${Config.repo}/commit/$newSha")
    }

    private fun putContents(
        path: String,
        contentBase64: String,
        message: String,
        branch: String,
        sha: String?
    ): JSONObject {
        val body = JSONObject()
            .put("message", message)
            .put("content", contentBase64)
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        return json("${reposPath()}/contents/${encPath(path)}", "PUT", body)
    }

    private fun resolveRef(branch: String): String? {
        return try {
            val obj = json("${reposPath()}/git/ref/heads/${encPath(branch)}")
            obj.optJSONObject("object")?.optString("sha")
        } catch (_: GitHubException) {
            null
        }
    }

    // ---------------------------------------------------------------- actions

    fun triggerWorkflow(workflowFile: String, ref: String) {
        requireRepo()
        json(
            "${reposPath()}/actions/workflows/${enc(workflowFile)}/dispatches",
            "POST",
            JSONObject().put("ref", ref)
        )
    }

    fun listRuns(perPage: Int = 10): JSONArray {
        requireRepo()
        val obj = json("${reposPath()}/actions/runs?per_page=$perPage")
        return obj.optJSONArray("workflow_runs") ?: JSONArray()
    }

    fun getRun(runId: Long): JSONObject {
        requireRepo()
        return json("${reposPath()}/actions/runs/$runId")
    }

    fun getRunJobs(runId: Long): JSONArray {
        requireRepo()
        val obj = json("${reposPath()}/actions/runs/$runId/jobs")
        return obj.optJSONArray("jobs") ?: JSONArray()
    }

    fun downloadRunLogs(runId: Long): String {
        requireRepo()
        val request = api("${reposPath()}/actions/runs/$runId/logs")
        val bytes = execute(request).use { it.body?.bytes() ?: ByteArray(0) }
        if (bytes.isEmpty()) throw GitHubException("日志为空（构建可能尚未结束）")
        return unzipToText(bytes)
    }

    fun listArtifacts(runId: Long): JSONArray {
        requireRepo()
        val obj = json("${reposPath()}/actions/runs/$runId/artifacts")
        return obj.optJSONArray("artifacts") ?: JSONArray()
    }

    fun downloadArtifactZip(artifactId: Long): ByteArray {
        requireRepo()
        val request = api("${reposPath()}/actions/artifacts/$artifactId/zip")
        return execute(request).use { it.body?.bytes() ?: ByteArray(0) }
    }

    private fun unzipToText(bytes: ByteArray): String {
        val out = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out.append("===== ").append(entry.name).append(" =====\n")
                    out.append(zip.readBytes().toString(Charsets.UTF_8))
                    out.append("\n")
                }
                entry = zip.nextEntry
            }
        }
        return out.toString()
    }

    companion object {
        fun base64Of(content: String): String =
            Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        fun base64OfBytes(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
