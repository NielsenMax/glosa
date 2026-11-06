package com.maxi.glosa.update

import com.maxi.glosa.net.HttpJson
import com.maxi.glosa.net.NetworkException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseFeedTest {
    private val server = MockWebServer()
    private lateinit var feed: ReleaseFeed

    private val recorded: JSONObject get() = JSONObject(javaClass.getResource("/github/latest_release.json")!!.readText())

    @Before
    fun setUp() {
        server.start()
        feed = ReleaseFeed(HttpJson(userAgent = "Glosa/test"), endpoint = server.url("/repos/NielsenMax/glosa/releases/latest").toString())
    }

    @After
    fun tearDown() = server.close()

    private fun respond(release: JSONObject) = server.enqueue(MockResponse(body = release.toString()))

    @Test
    fun `reads the version and the APK of the latest release`() = runTest {
        respond(recorded)

        val release = feed.latest()!!

        assertEquals(6L, release.versionCode)
        assertEquals("0.3.6", release.versionName)
        assertEquals("https://github.com/NielsenMax/glosa/releases/download/v0.3.6/Glosa.apk", release.apkUrl)
        assertTrue(release.notes.startsWith("Actualizaciones automáticas desde GitHub Releases"))
    }

    @Test
    fun `offers nothing while the repo has no release`() = runTest {
        server.enqueue(MockResponse(code = 404))

        assertNull(feed.latest())
    }

    @Test
    fun `offers nothing when the release has no APK`() = runTest {
        respond(recorded.put("assets", JSONArray()))

        assertNull(feed.latest())
    }

    @Test
    fun `rejects a tag that does not end in a version number`() {
        respond(recorded.put("tag_name", "v0.3-beta"))

        val failure = assertThrows(NetworkException::class.java) { runBlocking { feed.latest() } }

        assertEquals("la versión publicada «0.3-beta» no termina en un número de versión", failure.message)
    }
}
