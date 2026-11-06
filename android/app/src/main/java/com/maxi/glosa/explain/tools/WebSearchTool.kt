package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool
import com.maxi.glosa.explain.ToolException
import com.maxi.glosa.net.HttpJson
import com.maxi.glosa.net.HttpStatusException
import org.json.JSONObject

/** Web search through Tavily, whose results already come as short text excerpts a small model can read. */
class WebSearchTool(
    private val http: HttpJson,
    private val apiKey: String,
    private val endpoint: String = "https://api.tavily.com/search",
) : Tool {
    override val kind = SourceKind.Web

    override suspend fun run(query: String, context: LookupContext): List<Finding> {
        val body = JSONObject()
            .put("query", query)
            .put("search_depth", "basic")
            .put("max_results", MAX_RESULTS)
        val response = try {
            http.post(endpoint, body, headers = mapOf("Authorization" to "Bearer $apiKey"))
        } catch (e: HttpStatusException) {
            throw when (e.status) {
                401, 403 -> ToolException("la clave de Tavily no es válida; revisala en Ajustes", e)
                429 -> ToolException("Tavily recibió demasiadas búsquedas seguidas; probá en un rato", e)
                432, 433 -> ToolException("se agotaron las búsquedas de Tavily de este mes", e)
                else -> e
            }
        }
        val results = response.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map(results::getJSONObject).mapNotNull { result ->
            val content = result.optString("content").trim()
            if (content.isEmpty()) return@mapNotNull null
            Finding(
                source = Source(SourceKind.Web, title = result.optString("title").ifEmpty { result.getString("url") }, url = result.getString("url")),
                text = content.take(RESULT_CHARS),
            )
        }
    }

    private companion object {
        const val MAX_RESULTS = 3
        const val RESULT_CHARS = 600
    }
}
