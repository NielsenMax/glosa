package com.maxi.glosa.update

import com.maxi.glosa.net.HttpJson
import com.maxi.glosa.net.HttpStatusException
import com.maxi.glosa.net.NetworkException

data class AppRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

/** The newest release on GitHub, which CI publishes on every push to main as tag `v0.3.<commit count>` with `Glosa.apk`. */
class ReleaseFeed(
    private val http: HttpJson,
    private val endpoint: String = "https://api.github.com/repos/NielsenMax/glosa/releases/latest",
) {
    /** Null while the repo has no release with an APK yet. */
    suspend fun latest(): AppRelease? {
        val release = try {
            http.get(endpoint)
        } catch (e: HttpStatusException) {
            if (e.status == 404) return null else throw e
        }
        val versionName = release.getString("tag_name").removePrefix("v")
        val versionCode = versionName.substringAfterLast('.').toLongOrNull()
            ?: throw NetworkException("la versión publicada «$versionName» no termina en un número de versión")
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == APK_NAME }
            ?: return null
        return AppRelease(versionCode, versionName, apk.getString("browser_download_url"), release.optString("body").trim())
    }

    private companion object {
        const val APK_NAME = "Glosa.apk"
    }
}
