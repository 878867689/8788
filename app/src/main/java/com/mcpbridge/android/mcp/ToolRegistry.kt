package com.mcpbridge.android.mcp

import org.json.JSONArray
import org.json.JSONObject

/** MCP 工具的返回体。 */
data class ToolResult(val text: String, val isError: Boolean = false)

/** 一个可被 AI 客户端调用的工具。 */
class McpTool(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val handler: (JSONObject) -> ToolResult
)

/** JSON-RPC 层要返回给客户端的错误。 */
class McpException(val code: Int, override val message: String) : RuntimeException(message)

class ToolRegistry(private val tools: List<McpTool>) {

    fun find(name: String): McpTool? = tools.firstOrNull { it.name == name }

    fun listJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("inputSchema", t.schema)
            )
        }
        return arr
    }
}

/** 构造 JSON Schema 的小工具。 */
object Schemas {

    fun obj(
        properties: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JSONObject {
        val props = JSONObject()
        properties.forEach { (k, v) -> props.put(k, v) }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        } else {
            schema.put("required", JSONArray())
        }
        schema.put("additionalProperties", false)
        return schema
    }

    fun str(description: String, default: String? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", description)
        if (default != null) o.put("default", default)
        return o
    }

    fun int(description: String, default: Int? = null): JSONObject {
        val o = JSONObject().put("type", "integer").put("description", description)
        if (default != null) o.put("default", default)
        return o
    }

    fun bool(description: String, default: Boolean? = null): JSONObject {
        val o = JSONObject().put("type", "boolean").put("description", description)
        if (default != null) o.put("default", default)
        return o
    }

    fun strArray(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string"))

    fun fileArray(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put(
            "items",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", str("仓库内的相对路径，例如 app/build.gradle.kts"))
                    .put("content", str("文件完整文本内容（utf-8）"))
                    .put("base64", str("可选：文件的 base64 内容，用于二进制文件")))
                .put("required", JSONArray().put("path"))
        )
}
