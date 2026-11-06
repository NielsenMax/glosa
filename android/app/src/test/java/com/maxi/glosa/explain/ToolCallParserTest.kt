package com.maxi.glosa.explain

import com.maxi.glosa.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallParserTest {
    private val everyTool = SourceKind.entries.toSet()

    @Test
    fun `reads one lookup per line`() {
        val calls = ToolCallParser.parse("WIKIPEDIA: market maker\nLIBRO: specialist", everyTool)

        assertEquals(listOf(ToolCall(SourceKind.Wikipedia, "market maker"), ToolCall(SourceKind.Book, "specialist")), calls)
    }

    @Test
    fun `reads a dictionary lookup`() {
        assertEquals(listOf(ToolCall(SourceKind.Dictionary, "hedge")), ToolCallParser.parse("DICCIONARIO: hedge", everyTool))
    }

    @Test
    fun `answers straight away when the model says RESPONDER`() {
        assertEquals(emptyList<ToolCall>(), ToolCallParser.parse("RESPONDER", everyTool))
    }

    @Test
    fun `answers straight away when the reply is prose instead of the format`() {
        assertEquals(emptyList<ToolCall>(), ToolCallParser.parse("Creo que convendría buscar qué es un especialista.", everyTool))
    }

    @Test
    fun `tolerates list markers, arrows, quotes and lowercase keywords`() {
        val calls = ToolCallParser.parse("1. Libro: «bid/ask spread»\n→ calcular: (10.05 - 10) / 10 * 100\n- web - \"NYSE specialist\"", everyTool)

        assertEquals(
            listOf(
                ToolCall(SourceKind.Book, "bid/ask spread"),
                ToolCall(SourceKind.Calculator, "(10.05 - 10) / 10 * 100"),
                ToolCall(SourceKind.Web, "NYSE specialist"),
            ),
            calls,
        )
    }

    @Test
    fun `ignores tools that are not available`() {
        val calls = ToolCallParser.parse("WEB: tick size pilot\nWIKIPEDIA: tick size", setOf(SourceKind.Wikipedia))

        assertEquals(listOf(ToolCall(SourceKind.Wikipedia, "tick size")), calls)
    }

    @Test
    fun `keeps at most three distinct lookups`() {
        val reply = "LIBRO: dealer\nLIBRO: Dealer\nWIKIPEDIA: dealer\nNOTAS: dealer\nWEB: dealer"

        val calls = ToolCallParser.parse(reply, everyTool)

        assertEquals(
            listOf(ToolCall(SourceKind.Book, "dealer"), ToolCall(SourceKind.Wikipedia, "dealer"), ToolCall(SourceKind.Notes, "dealer")),
            calls,
        )
    }
}
