package com.mcpbridge.android.mcp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 最小但可用的 MCP Server，走 Streamable HTTP 传输：
 *  - POST /mcp   JSON-RPC 2.0 请求/响应
 *  - GET  /mcp   本实现不提供 SSE 流，返回 405（协议允许）
 *  - GET  /health 简单探活
 *
 * 鉴权：Authorization: Bearer <token>
 */
class McpServer(
    port: Int,
    private val authToken: String,
    private val registry: ToolRegistry
) : NanoHTTPD(port) {

    private val protocolVersion = "2025-06-18"
    private val sessions = mutableSetOf<String>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        return try {
            when {
                session.method == Method.OPTIONS -> cors(
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                )

                uri == "/" || uri == "/health" -> cors(json(Response.Status.OK, healthJson().toString()))

                uri != "/mcp" -> cors(
                    json(Response.Status.NOT_FOUND, errorBody(null, -32601, "Unknown endpoint: $uri"))
                )

                !authorized(session) -> {
                    val resp = json(
                        Response.Status.UNAUTHORIZED,
                        errorBody(null, -32001, "Unauthorized: missing or invalid bearer token")
                    )
                    resp.addHeader("WWW-Authenticate", "Bearer")
                    cors(resp)
                }

                session.method == Method.POST -> cors(handlePost(session))

                session.method == Method.GET -> cors(
                    newFixedLengthResponse(
                        Response.Status.METHOD_NOT_ALLOWED,
                        "text/plain; charset=utf-8",
                        "This server does not expose a server-sent event stream. Send JSON-RPC requests with POST /mcp."
                    )
                )

                session.method == Method.DELETE -> cors(
                    json(Response.Status.OK, "{}")
                )

                else -> cors(
                    newFixedLengthResponse(
                        Response.Status.METHOD_NOT_ALLOWED,
                        "text/plain; charset=utf-8",
                        "Method not allowed"
                    )
                )
            }
        } catch (t: Throwable) {
            cors(json(Response.Status.INTERNAL_ERROR, errorBody(null, -32603, t.message ?: "Internal error")))
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val raw = readBody(session)
        val request = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return json(Response.Status.BAD_REQUEST, errorBody(null, -32700, "Parse error: body is not valid JSON"))
        }

        val method = request.optString("method", "")
        val id: Any? = if (request.has("id") && !request.isNull("id")) request.opt("id") else null

        // 通知类消息（无 id），例如 notifications/initialized
        if (id == null) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
        }

        val params = request.optJSONObject("params") ?: JSONObject()

        val body = try {
            val result = dispatch(method, params)
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString()
        } catch (e: McpException) {
            errorBody(id, e.code, e.message)
        } catch (t: Throwable) {
            errorBody(id, -32603, t.message ?: "Internal error")
        }

        val response = json(Response.Status.OK, body)
        sessionId(session, method)?.let { response.addHeader("Mcp-Session-Id", it) }
        if (method == "initialize") {
            response.addHeader("MCP-Protocol-Version", protocolVersion)
        }
        return response
    }

    private fun dispatch(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "initialize" -> {
                val clientVersion = params.optString("protocolVersion").ifBlank { protocolVersion }
                JSONObject()
                    .put("protocolVersion", clientVersion)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject()
                        .put("name", "mcp-android-bridge")
                        .put("version", "1.0.0"))
                    .put("instructions", INSTRUCTIONS)
            }

            "ping" -> JSONObject()

            "tools/list" -> JSONObject().put("tools", registry.listJson())

            "tools/call" -> {
                val name = params.optString("name")
                if (name.isBlank()) throw McpException(-32602, "Missing tool name")
                val tool = registry.find(name) ?: throw McpException(-32602, "Unknown tool: $name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val result = try {
                    tool.handler(args)
                } catch (t: Throwable) {
                    ToolResult("工具 $name 执行失败：${t.message}", isError = true)
                }
                JSONObject()
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", result.text)))
                    .put("isError", result.isError)
            }

            "resources/list" -> JSONObject().put("resources", JSONArray())

            "prompts/list" -> JSONObject().put("prompts", JSONArray())

            "logging/setLevel" -> JSONObject()

            else -> throw McpException(-32601, "Method not found: $method")
        }
    }

    private fun sessionId(session: IHTTPSession, method: String): String? {
        if (method != "initialize") return null
        val sid = UUID.randomUUID().toString()
        sessions.add(sid)
        return sid
    }

    private fun authorized(session: IHTTPSession): Boolean {
        if (authToken.isBlank()) return false
        val header = session.headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value
            ?: return false
        val provided = header.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
        return provided == authToken
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            map["postData"].orEmpty()
        } catch (_: Exception) {
            try {
                session.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun healthJson(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("server", "mcp-android-bridge")
        .put("protocolVersion", protocolVersion)
        .put("endpoint", "/mcp")
        .put("sessions", sessions.size)

    private fun errorBody(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version"
        )
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS")
        response.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
        return response
    }

    companion object {
        private const val INSTRUCTIONS =
            "本地安卓 MCP 桥。能力分两层：" +
                "(1) 本机文件：list_local_dir / read_local_file / write_local_file / " +
                "delete_local_path / move_local_path / search_local_dir，可直接增删改手机里的源码，再 push_local_project 上传；" +
                "(2) 远程仓库与构建：push_files 读写 GitHub 仓库源码、trigger_build 触发 Actions 编译、" +
                "build_logs 拉日志、diagnose_build_log 定位缺失项、list_artifacts / download_apk 取 APK。" +
                "修复陌生工程闭环：search_local_dir 定位 -> read_local_file / read_repo_file 看懂 -> " +
                "write_local_file / push_files 修补（缺什么补什么）-> 编译 -> 看日志继续修 -> 直到 download_apk 成功。"
    }
}
