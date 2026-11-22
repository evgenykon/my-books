package io.github.evgenykon.mybooks.update

import io.github.evgenykon.mybooks.util.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** A published release that is newer than the running build. */
data class AppUpdate(
    val version: String,
    val msiUrl: String,
    val msiName: String,
    val size: Long,
)

/**
 * Checks the public GitHub release feed. The repository is public, so no token is needed and
 * nothing about the user leaves the machine; the MSI is downloaded straight from the release.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/evgenykon/my-books/releases/latest"

    val currentVersion: String by lazy {
        UpdateChecker::class.java.getResourceAsStream("/app-version.txt")
            ?.bufferedReader()?.use { it.readText().trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: "0.0.0"
    }

    fun check(): AppUpdate? {
        val body = runCatching { httpGet(API) }.getOrElse { error ->
            AppLog.info("update check failed: ${error.message}")
            return null
        }
        return parse(body)?.takeIf { isNewer(it.version, currentVersion) }
    }

    /** Parses the release payload; separated from the request so it can be tested. */
    fun parse(body: String): AppUpdate? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return null
        val assets = root["assets"]?.jsonArray ?: return null
        val msi = assets.map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".msi", ignoreCase = true) == true }
            ?: return null
        AppUpdate(
            version = tag,
            msiUrl = msi["browser_download_url"]?.jsonPrimitive?.content ?: return null,
            msiName = msi["name"]?.jsonPrimitive?.content ?: "MyBooks.msi",
            size = msi["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    /** "0.4.6" is newer than "0.4.5"; non-numeric tails are ignored. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.trim().removePrefix("v").split('.', '-', '+')
            .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "my-books-desktop")
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
