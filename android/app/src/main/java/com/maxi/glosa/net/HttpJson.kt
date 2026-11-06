package com.maxi.glosa.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** A request that could not be completed, with a message the reader can understand. */
open class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

class HttpStatusException(val status: Int) : NetworkException("el servidor respondió $status")

/** Minimal JSON over HTTPS; every failure surfaces as a [NetworkException]. */
class HttpJson(private val userAgent: String) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject = request(url, headers, body = null)

    suspend fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        request(url, headers, body)

    private suspend fun request(url: String, headers: Map<String, String>, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw HttpStatusException(status)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (e: UnknownHostException) {
                throw NetworkException("no hay conexión a internet", e)
            } catch (e: IOException) {
                throw NetworkException("falló la conexión (${e.message})", e)
            } catch (e: JSONException) {
                throw NetworkException("la respuesta no se pudo leer", e)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 8_000
    }
}
