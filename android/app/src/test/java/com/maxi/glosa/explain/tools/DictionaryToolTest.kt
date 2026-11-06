package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.net.HttpJson
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DictionaryToolTest {
    private val english = MockWebServer()
    private val spanish = MockWebServer()
    private lateinit var tool: DictionaryTool

    private val aboutTheSpread = LookupContext(
        passage = "Dealers quote two prices. The difference between the bidding price and the asking price is the spread.",
        question = "Explicámelo",
    )

    private fun fixture(name: String) = javaClass.getResource("/wiktionary/$name")!!.readText()

    private fun serve(server: MockWebServer, responses: Map<String, Pair<Int, String>>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = responses.keys.firstOrNull { request.url.toString().contains(it) } ?: return MockResponse(code = 500)
                val (code, fixture) = responses.getValue(key)
                return MockResponse(code = code, body = fixture(fixture))
            }
        }
    }

    @Before
    fun setUp() {
        english.start()
        spanish.start()
        tool = DictionaryTool(
            HttpJson(userAgent = "Glosa/test"),
            bookLanguage = "en",
            englishWiktionary = english.url("/").toString().trimEnd('/'),
            spanishWiktionary = spanish.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        english.close()
        spanish.close()
    }

    @Test
    fun `keeps the senses that fit the passage out of the many the word has`() = runTest {
        serve(english, mapOf("/definition/spread" to (200 to "en_spread.json")))
        serve(spanish, mapOf("titles=spread" to (200 to "es_spread.json")))

        val senses = tool.run("spread", aboutTheSpread).single { it.source.title == "Wiktionary: spread" }.text

        assertTrue(senses.contains("Noun: The difference between bidding and asking price."))
        assertFalse(senses.contains("cattle ranch"))
        assertFalse(senses.contains("butter"))
    }

    @Test
    fun `gives the Spanish meanings of the word`() = runTest {
        serve(english, mapOf("/definition/spread" to (200 to "en_spread.json")))
        serve(spanish, mapOf("titles=spread" to (200 to "es_spread.json")))

        val glosses = tool.run("spread", aboutTheSpread).first()

        assertEquals(Source(SourceKind.Dictionary, "Wikcionario: spread", url = "${spanish.url("/").toString().trimEnd('/')}/wiki/spread"), glosses.source)
        assertEquals(
            "spread en español — Verbo: Esparcir; Desplegar; Extender; Difundir. Sustantivo: Extensión, difusión, despliegue, esparcimiento; Capacidad de expansión",
            glosses.text,
        )
    }

    @Test
    fun `finds nothing for a word neither Wiktionary has`() = runTest {
        serve(english, mapOf("/definition/" to (404 to "en_not_found.json")))
        serve(spanish, mapOf("titles=" to (200 to "es_missing.json")))

        assertEquals(emptyList<Any>(), tool.run("zzqxnotaword", aboutTheSpread))
    }
}
