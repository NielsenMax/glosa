package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.ToolException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WikipediaToolTest {
    private val server = MockWebServer()
    private lateinit var tool: WikipediaTool

    private val aboutTheSpread = LookupContext(
        passage = "Dealers quote two prices. The difference between the bid price and the ask price is the bid/ask spread. It is their income.",
        question = "¿De qué viven los market makers?",
    )

    @Before
    fun setUp() {
        server.start()
        tool = WikipediaTool(HttpJson(userAgent = "Glosa/test (https://github.com/NielsenMax/glosa)"), listOf("en", "es")) { language ->
            server.url("/$language").toString().trimEnd('/')
        }
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String) =
        server.enqueue(MockResponse(body = javaClass.getResource("/wikipedia/$fixture")!!.readText()))

    @Test
    fun `returns the top article with its introduction`() = runTest {
        respond("en_market_maker.json")

        val finding = tool.run("market maker", aboutTheSpread).single()

        assertEquals(Source(SourceKind.Wikipedia, "Market maker", url = "https://en.wikipedia.org/wiki/Market_maker"), finding.source)
        assertTrue(finding.text.startsWith("A market maker or liquidity provider is a company or individual"))
    }

    @Test
    fun `adds the sections of the article that match the passage`() = runTest {
        respond("en_market_maker.json")

        val text = tool.run("market maker", aboutTheSpread).single().text

        assertTrue(text.contains("§ Income of market makers: The income of a market maker is the difference between the bid price"))
        assertFalse(text.contains("§ See also"))
    }

    @Test
    fun `searches the book's language first and identifies itself as Wikimedia asks`() = runTest {
        respond("en_market_maker.json")

        tool.run("market maker", aboutTheSpread)

        val request = server.takeRequest()
        assertEquals("/en/w/api.php", request.url.encodedPath)
        assertEquals("market maker", request.url.queryParameter("gsrsearch"))
        assertEquals("Glosa/test (https://github.com/NielsenMax/glosa)", request.headers["User-Agent"])
    }

    @Test
    fun `falls back to Spanish when the book's language has no article`() = runTest {
        respond("en_no_results.json")
        respond("es_creador_de_mercado.json")

        val findings = tool.run("creador de mercado", aboutTheSpread)

        assertEquals(listOf("Creador del mercado"), findings.map { it.source.title })
    }

    @Test
    fun `finds nothing when no language has an article`() = runTest {
        respond("en_no_results.json")
        respond("en_no_results.json")

        assertEquals(emptyList<Any>(), tool.run("zzqx", aboutTheSpread))
    }

    @Test
    fun `fails the lookup when Wikipedia answers with an error`() {
        server.enqueue(MockResponse(code = 503))

        val failure = assertThrows(ToolException::class.java) { runBlocking { tool.run("market maker", aboutTheSpread) } }

        assertEquals("el servidor respondió 503", failure.message)
    }
}
