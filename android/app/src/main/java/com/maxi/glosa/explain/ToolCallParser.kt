package com.maxi.glosa.explain

import com.maxi.glosa.data.SourceKind

/**
 * Reads the planner's reply: one `HERRAMIENTA: consulta` per line, or `RESPONDER`. Plain text instead of
 * ML Kit function calling, which needs Gemini Nano 4 (docs/adr/0005); anything off-format means "answer now".
 */
object ToolCallParser {
    const val MAX_CALLS = 3

    val keywords: Map<SourceKind, String> = mapOf(
        SourceKind.Book to "LIBRO",
        SourceKind.Wikipedia to "WIKIPEDIA",
        SourceKind.Dictionary to "DICCIONARIO",
        SourceKind.Web to "WEB",
        SourceKind.Notes to "NOTAS",
        SourceKind.Calculator to "CALCULAR",
    )

    private val line = Regex(
        "^[\\s\\-*•·>→\\d.)]*(${keywords.values.joinToString("|")})\\s*[:：\\-–—]\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val quotes = Regex("^[\"'«»“”`]+|[\"'«»“”`]+$")

    fun parse(reply: String, available: Set<SourceKind>): List<ToolCall> = reply.lineSequence()
        .mapNotNull { line.find(it.trim()) }
        .mapNotNull { match ->
            val keyword = match.groupValues[1].uppercase()
            val tool = keywords.entries.first { it.value == keyword }.key
            val query = match.groupValues[2].trim().replace(quotes, "").trim().take(MAX_QUERY_CHARS)
            ToolCall(tool, query).takeIf { tool in available && query.isNotEmpty() }
        }
        .distinctBy { it.key }
        .take(MAX_CALLS)
        .toList()

    private const val MAX_QUERY_CHARS = 120
}
