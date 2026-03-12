package ua.com.myaiagent.data.pipeline

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.com.myaiagent.data.tasks.ToolDefinition
import ua.com.myaiagent.data.tasks.ToolParameters
import ua.com.myaiagent.data.tasks.ToolProperty
import java.io.File
import java.net.URLEncoder

enum class SearchMode(val label: String, val description: String) {
    WIKIPEDIA("Wikipedia", "GET-запрос к Wikipedia REST API"),
    LLM("LLM", "Генерация через языковую модель"),
}

private val pipelineJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun pipelineTools(searchMode: SearchMode): List<ToolDefinition> = listOf(
    ToolDefinition(
        name = "search",
        description = when (searchMode) {
            SearchMode.WIKIPEDIA -> "Search for information on Wikipedia. Returns an extract about the given query."
            SearchMode.LLM -> "Generate detailed information about the given topic using the language model's knowledge."
        },
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty("string", "The search query or topic to look up"),
            ),
            required = listOf("query"),
        ),
    ),
    ToolDefinition(
        name = "summarize",
        description = "Summarize text using LLM into a concise version with key facts only.",
        parameters = ToolParameters(
            properties = mapOf(
                "text" to ToolProperty("string", "The text to summarize"),
                "max_sentences" to ToolProperty("integer", "Target number of sentences in the summary (default 3)"),
            ),
            required = listOf("text"),
        ),
    ),
    ToolDefinition(
        name = "saveToFile",
        description = "Save content to a file in the app's private storage.",
        parameters = ToolParameters(
            properties = mapOf(
                "filename" to ToolProperty("string", "The filename to save to (e.g. 'mcp.md')"),
                "content" to ToolProperty("string", "The content to write to the file"),
            ),
            required = listOf("filename", "content"),
        ),
    ),
    ToolDefinition(
        name = "readFile",
        description = "Read the contents of a previously saved file from the app's private storage.",
        parameters = ToolParameters(
            properties = mapOf(
                "filename" to ToolProperty("string", "The filename to read (e.g. 'mcp.md')"),
            ),
            required = listOf("filename"),
        ),
    ),
)

class PipelineToolExecutor(
    private val httpClient: HttpClient,
    private val context: Context,
    private val apiKey: String,
) {
    suspend fun search(query: String, mode: SearchMode): String = when (mode) {
        SearchMode.WIKIPEDIA -> searchWikipedia(query)
        SearchMode.LLM -> searchLlm(query)
    }

    private suspend fun searchWikipedia(query: String): String {
        val hasCyrillic = query.any { it in '\u0400'..'\u04FF' }
        val lang = if (hasCyrillic) "ru" else "en"
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val responseText = httpClient.get(url).bodyAsText()
            val json = pipelineJson.parseToJsonElement(responseText).jsonObject
            // "type": "disambiguation" or missing extract means not found — try search API
            val extract = json["extract"]?.jsonPrimitive?.content
            if (!extract.isNullOrBlank()) {
                extract
            } else {
                searchWikipediaFallback(query, lang)
            }
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    private suspend fun searchWikipediaFallback(query: String, lang: String): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=1"
            val responseText = httpClient.get(searchUrl).bodyAsText()
            val json = pipelineJson.parseToJsonElement(responseText).jsonObject
            val title = json["query"]?.jsonObject
                ?.get("search")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("title")?.jsonPrimitive?.content
                ?: return "No information found for: $query"
            // Fetch summary for found title
            val titleEncoded = URLEncoder.encode(title, "UTF-8")
            val summaryText = httpClient.get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$titleEncoded").bodyAsText()
            val summaryJson = pipelineJson.parseToJsonElement(summaryText).jsonObject
            summaryJson["extract"]?.jsonPrimitive?.content ?: "No information found for: $query"
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    private suspend fun searchLlm(query: String): String {
        return try {
            val body = """
                {
                  "model": "gpt-4.1-mini",
                  "input": "Provide detailed factual information about: $query. Write 5-8 informative sentences.",
                  "max_output_tokens": 300
                }
            """.trimIndent()
            val responseText = httpClient.post("https://api.openai.com/v1/responses") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(body)
            }.bodyAsText()
            val json = pipelineJson.parseToJsonElement(responseText).jsonObject
            // Try output_text first, then output[].content[].text
            json["output_text"]?.jsonPrimitive?.content
                ?: json["output"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "message" }
                    ?.jsonObject?.get("content")?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: "LLM search returned no content"
        } catch (e: Exception) {
            "LLM search failed: ${e.message}"
        }
    }

    suspend fun summarize(text: String, maxSentences: Int = 3): String {
        if (text.isBlank()) return ""
        return try {
            val prompt = "Write EXACTLY $maxSentences short sentences summarizing only the most essential facts from the text below. No intro, no conclusion, just the facts. Output only the $maxSentences sentences, nothing else.\n\nText:\n$text"
            val body = """{"model":"gpt-4.1-mini","input":${org.json.JSONObject.quote(prompt)},"max_output_tokens":120}"""
            val responseText = httpClient.post("https://api.openai.com/v1/responses") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(body)
            }.bodyAsText()
            val json = pipelineJson.parseToJsonElement(responseText).jsonObject
            json["output_text"]?.jsonPrimitive?.content
                ?: json["output"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "message" }
                    ?.jsonObject?.get("content")?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: "Summary unavailable"
        } catch (e: Exception) {
            "Summarize failed: ${e.message}"
        }
    }

    fun saveToFile(filename: String, content: String): String {
        return try {
            val file = File(context.filesDir, filename)
            file.writeText(content)
            file.absolutePath
        } catch (e: Exception) {
            throw Exception("Failed to save file: ${e.message}")
        }
    }

    fun readFile(filename: String): String {
        val file = File(context.filesDir, filename)
        if (!file.exists()) throw Exception("File not found: $filename")
        return file.readText().ifBlank { "(empty file)" }
    }
}
