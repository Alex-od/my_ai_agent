package ua.com.myaiagent.data.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import ua.com.myaiagent.data.tasks.ToolDefinition
import ua.com.myaiagent.data.tasks.ToolParameters
import ua.com.myaiagent.data.tasks.ToolProperty

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject = buildJsonObject {},
)

@Serializable
data class JsonRpcResponse(
    val id: Int = 0,
    val result: JsonObject? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(val code: Int, val message: String)

data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = buildJsonObject {},
)

fun McpTool.toToolDefinition(): ToolDefinition {
    val props = inputSchema["properties"]?.jsonObject ?: buildJsonObject {}
    val required = inputSchema["required"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content }
        ?: emptyList()
    val toolProps = props.entries.associate { (key, value) ->
        val obj = value.jsonObject
        key to ToolProperty(
            type = obj["type"]?.jsonPrimitive?.content ?: "string",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
        )
    }
    return ToolDefinition(
        name = name,
        description = description,
        parameters = ToolParameters(properties = toolProps, required = required),
    )
}

class McpClient(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var baseUrl = ""

    suspend fun connect(url: String): String {
        baseUrl = url.trimEnd('/')
        val req = JsonRpcRequest(
            id = 1,
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                putJsonObject("clientInfo") {
                    put("name", "AndroidMcpClient")
                    put("version", "1.0")
                }
            },
        )
        val resp = post(req)
        return resp.result?.get("serverInfo")?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "MCP Server"
    }

    suspend fun listTools(): List<McpTool> {
        check(baseUrl.isNotEmpty()) { "Call connect() first" }
        val resp = post(JsonRpcRequest(id = 2, method = "tools/list"))
        val arr = resp.result?.get("tools")?.jsonArray ?: return emptyList()
        return arr.map { el ->
            val o = el.jsonObject
            McpTool(
                name = o["name"]?.jsonPrimitive?.content ?: "",
                description = o["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = o["inputSchema"]?.jsonObject ?: buildJsonObject {},
            )
        }
    }

    suspend fun callTool(name: String, arguments: String): String {
        check(baseUrl.isNotEmpty()) { "Call connect() first" }
        val argsJson = runCatching { json.parseToJsonElement(arguments).jsonObject }
            .getOrDefault(buildJsonObject {})
        val resp = post(
            JsonRpcRequest(
                id = 3,
                method = "tools/call",
                params = buildJsonObject {
                    put("name", name)
                    put("arguments", argsJson)
                },
            )
        )
        val content = resp.result?.get("content")?.jsonArray
        return content?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: "No result"
    }

    private suspend fun post(req: JsonRpcRequest): JsonRpcResponse {
        val r = client.post("$baseUrl/mcp") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(req))
        }
        if (!r.status.isSuccess()) error("HTTP ${r.status.value}")
        val rpc: JsonRpcResponse = json.decodeFromString(r.bodyAsText())
        rpc.error?.let { error("MCP error ${it.code}: ${it.message}") }
        return rpc
    }
}
