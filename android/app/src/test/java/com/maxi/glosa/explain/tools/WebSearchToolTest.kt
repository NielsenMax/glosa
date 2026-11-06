package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.ToolException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** The stub payload follows Tavily's documented response; there is no key in the repo to record a live one. */
class WebSearchToolTest {
    private val server = MockWebServer()
    private val noContext = LookupContext(passage = "", question = "")
    private lateinit var tool: WebSearchTool

    @Before
    fun setUp() {
        server.start()
        tool = WebSearchTool(HttpJson(userAgent = "Glosa/test"), apiKey = "tvly-test-key", endpoint = server.url("/search").toString())
    }

    @After
    fun tearDown() = server.close()

    private fun fixture(name: String) = javaClass.getResource("/tavily/$name")!!.readText()

    @Test
    fun `returns the results that have text, as excerpts with their address`() = runTest {
        server.enqueue(MockResponse(body = fixture("search_bid_ask_spread.json")))

        val findings = tool.run("bid-ask spread", noContext)

        assertEquals(
            listOf(
                Source(SourceKind.Web, "Bid-Ask Spread: Definition and How It Works", url = "https://www.investopedia.com/terms/b/bid-askspread.asp"),
                Source(SourceKind.Web, "Bid–ask spread - Wikipedia", url = "https://en.wikipedia.org/wiki/Bid%E2%80%93ask_spread"),
            ),
            findings.map { it.source },
        )
    }

    @Test
    fun `sends the key and asks for a basic search`() = runTest {
        server.enqueue(MockResponse(body = fixture("search_bid_ask_spread.json")))

        tool.run("bid-ask spread", noContext)

        val request = server.takeRequest()
        assertEquals("Bearer tvly-test-key", request.headers["Authorization"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("bid-ask spread", body.getString("query"))
        assertEquals("basic", body.getString("search_depth"))
    }

    @Test
    fun `explains a rejected key`() {
        server.enqueue(MockResponse(code = 401))

        val failure = assertThrows(ToolException::class.java) { runBlocking { tool.run("bid-ask spread", noContext) } }

        assertEquals("la clave de Tavily no es válida; revisala en Ajustes", failure.message)
    }

    @Test
    fun `explains a spent monthly quota`() {
        server.enqueue(MockResponse(code = 432))

        val failure = assertThrows(ToolException::class.java) { runBlocking { tool.run("bid-ask spread", noContext) } }

        assertEquals("se agotaron las búsquedas de Tavily de este mes", failure.message)
    }
}
