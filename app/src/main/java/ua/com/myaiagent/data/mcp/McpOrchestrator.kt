package ua.com.myaiagent.data.mcp

import io.ktor.client.HttpClient

class McpOrchestrator(private val httpClient: HttpClient) {

    private val clients = mutableMapOf<String, McpClient>()      // url → client
    private val toolToUrl = mutableMapOf<String, String>()       // toolName → serverUrl
    private val allTools = mutableListOf<McpTool>()
    val serverNames = mutableMapOf<String, String>()             // url → serverName

    /** Connect to all servers. Returns Map<url, "OK" | "ERROR: ..."> */
    suspend fun connectAll(urls: List<String>): Map<String, String> {
        clients.clear()
        toolToUrl.clear()
        allTools.clear()
        serverNames.clear()

        val results = mutableMapOf<String, String>()

        for (url in urls) {
            val client = McpClient(httpClient)
            try {
                val name = client.connect(url)
                val tools = client.listTools()
                clients[url] = client
                serverNames[url] = name
                tools.forEach { tool ->
                    toolToUrl[tool.name] = url
                    allTools.add(tool)
                }
                results[url] = "OK"
            } catch (e: Exception) {
                results[url] = "ERROR: ${e.message}"
            }
        }

        return results
    }

    /** All tools from all connected servers (pass to OpenAI). */
    fun listAllTools(): List<McpTool> = allTools.toList()

    /**
     * Route the tool call to the correct server using the routing table.
     * Returns the tool result as a String.
     */
    suspend fun callTool(name: String, arguments: String): String {
        val url = toolToUrl[name] ?: error("No server found for tool: $name")
        val client = clients[url] ?: error("Client not connected for url: $url")
        return client.callTool(name, arguments)
    }

    /** Server name for a given tool name (for logging). */
    fun serverNameForTool(name: String): String {
        val url = toolToUrl[name] ?: return "Unknown"
        return serverNames[url] ?: url
    }

    fun disconnect() {
        clients.clear()
        toolToUrl.clear()
        allTools.clear()
        serverNames.clear()
    }

    fun isConnected(): Boolean = clients.isNotEmpty()
}
