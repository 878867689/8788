package com.mcpbridge.android

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 全局配置，存在 SharedPreferences 里。
 * 注意：GitHub PAT 只保存在本机，不要提交到仓库。
 */
object Config {

    private const val PREF_NAME = "mcp_bridge"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getString(key: String, def: String = ""): String =
        prefs.getString(key, def).orEmpty().trim()

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    var githubToken: String
        get() = getString("github_token")
        set(v) = putString("github_token", v)

    var owner: String
        get() = getString("owner")
        set(v) = putString("owner", v)

    var repo: String
        get() = getString("repo")
        set(v) = putString("repo", v)

    var branch: String
        get() = getString("branch", "main").ifBlank { "main" }
        set(v) = putString("branch", v.ifBlank { "main" })

    var workflowFile: String
        get() = getString("workflow", "build-apk.yml").ifBlank { "build-apk.yml" }
        set(v) = putString("workflow", v.ifBlank { "build-apk.yml" })

    var port: Int
        get() = prefs.getInt("port", 8388)
        set(v) = prefs.edit().putInt("port", v).apply()

    var authToken: String
        get() = getString("auth_token")
        set(v) = putString("auth_token", v)

    var lastRunId: Long
        get() = prefs.getLong("last_run_id", 0L)
        set(v) = prefs.edit().putLong("last_run_id", v).apply()

    /** 未配置访问令牌时生成一个，避免服务裸奔。 */
    fun ensureAuthToken(): String {
        if (authToken.isBlank()) {
            authToken = UUID.randomUUID().toString().replace("-", "")
        }
        return authToken
    }

    fun isGithubConfigured(): Boolean = githubToken.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    fun maskedToken(): String {
        val t = githubToken
        return when {
            t.isBlank() -> "(未配置)"
            t.length <= 8 -> "****"
            else -> t.take(4) + "****" + t.takeLast(4)
        }
    }

    fun summary(): String = buildString {
        append("owner=").append(owner.ifBlank { "(未配置)" }).append('\n')
        append("repo=").append(repo.ifBlank { "(未配置)" }).append('\n')
        append("branch=").append(branch).append('\n')
        append("workflow=").append(workflowFile).append('\n')
        append("github_token=").append(maskedToken()).append('\n')
        append("port=").append(port).append('\n')
        append("last_run_id=").append(if (lastRunId > 0) lastRunId.toString() else "(无)")
    }
}
