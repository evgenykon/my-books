package io.github.evgenykon.mybooks.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published in GitHub releases. */
data class AvailableUpdate(
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Looks up the newest release of the project on GitHub.
 *
 * The repository is public, so no token is needed; the APK asset is downloaded straight from
 * the release, which is exactly what a manual download would give.
 */
object UpdateChecker {

    private const val RELEASES = "https://api.github.com/repos/evgenykon/my-books/releases/latest"
    private const val APK_ASSET = "my-books-android.apk"

    private val json = Json { ignoreUnknownKeys = true }

    fun check(currentVersion: String): AvailableUpdate? {
        val body = get(RELEASES) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root.string("tag_name")?.trim()?.removePrefix("v") ?: return null
        if (compare(tag, currentVersion) <= 0) return null

        val assets = root["assets"]?.jsonArray?.map { it.jsonObject } ?: return null
        val apk = assets.firstOrNull { it.string("name") == APK_ASSET } ?: return null
        val url = apk.string("browser_download_url") ?: return null
        val size = apk.string("size")?.toLongOrNull() ?: 0L

        return AvailableUpdate(
            version = tag,
            pageUrl = root.string("html_url").orEmpty(),
            apkUrl = url,
            apkSize = size,
        )
    }

    /** Compares dotted versions; "0.3.10" is newer than "0.3.9". */
    fun compare(a: String, b: String): Int {
        val left = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val right = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MyBooks-Android")
        }
        return try {
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}
