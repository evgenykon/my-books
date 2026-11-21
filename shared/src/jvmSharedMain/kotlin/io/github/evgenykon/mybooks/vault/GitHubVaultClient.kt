package io.github.evgenykon.mybooks.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Remote file together with the blob sha needed to update it. */
data class RemoteFile(val content: ByteArray, val sha: String)

/** Raised when the branch changed under us; the caller re-reads and retries. */
class VaultConflictException : Exception("Ветка изменилась, повторите")

/** Any other failure talking to GitHub, with a message that can be shown to the user. */
class VaultApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal GitHub Contents/Git-data client for vault branches.
 *
 * Deliberately dependency free: `HttpURLConnection` is enough for the five calls the vault
 * needs, and the shared module stays free of HTTP libraries (important for Kotlin/Native).
 */
class GitHubVaultClient(
    token: String,
    owner: String,
    repo: String,
    private val defaultBranch: String = "main",
) {
    private val token = sanitize(token)
    private val owner = sanitize(owner)
    private val repo = sanitize(repo)

    private val json = Json { ignoreUnknownKeys = true }

    val configured: Boolean get() = token.isNotEmpty() && owner.isNotEmpty() && repo.isNotEmpty()

    private val api = "https://api.github.com/repos/$owner/$repo"

    // ---------------------------------------------------------------- branches

    /** All branch names of the repository (first 100). */
    fun listBranches(): List<String> {
        val body = request("GET", "$api/branches?per_page=100") ?: return emptyList()
        return json.parseToJsonElement(body).jsonArray.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNullSafe()
        }
    }

    /** Branches that already hold a `vault.enc`, newest first is up to the caller. */
    fun listVaults(): List<String> = listBranches().filter { it != defaultBranch && hasVault(it) }

    fun hasVault(branch: String): Boolean = runCatching {
        readFile(VAULT_FILE, branch) != null
    }.getOrDefault(false)

    /** Creates the branch from the head of the default branch. */
    fun createBranch(name: String) {
        val headSha = headSha(defaultBranch)
            ?: throw VaultApiException("Не удалось прочитать ветку $defaultBranch")
        val payload = buildJsonObject {
            put("ref", JsonPrimitive("refs/heads/$name"))
            put("sha", JsonPrimitive(headSha))
        }
        try {
            request("POST", "$api/git/refs", payload.toString())
        } catch (e: VaultApiException) {
            if (e.message?.contains("422") == true) {
                throw VaultApiException("Ветка «$name» уже существует")
            }
            throw e
        }
    }

    fun deleteBranch(name: String) {
        request("DELETE", "$api/git/refs/heads/$name")
    }

    /**
     * Creates a branch that holds only the given files — an orphan branch with its own root
     * commit, so a vault branch never carries a copy of the source tree.
     */
    fun createOrphanBranch(name: String, files: Map<String, ByteArray>, message: String) {
        val entries = files.map { (path, bytes) ->
            val sha = createBlob(bytes) ?: throw VaultApiException("Не удалось загрузить $path")
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("mode", JsonPrimitive("100644"))
                put("type", JsonPrimitive("blob"))
                put("sha", JsonPrimitive(sha))
            }
        }

        val treePayload = buildJsonObject {
            put("tree", JsonArray(entries))
        }
        val treeBody = request("POST", "$api/git/trees", treePayload.toString())
            ?: throw VaultApiException("Не удалось создать дерево файлов")
        val treeSha = json.parseToJsonElement(treeBody).jsonObject["sha"]?.jsonPrimitive?.content
            ?: throw VaultApiException("GitHub не вернул sha дерева")

        val commitPayload = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("tree", JsonPrimitive(treeSha))
            put("parents", JsonArray(emptyList()))
        }
        val commitBody = request("POST", "$api/git/commits", commitPayload.toString())
            ?: throw VaultApiException("Не удалось создать коммит")
        val commitSha = json.parseToJsonElement(commitBody).jsonObject["sha"]?.jsonPrimitive?.content
            ?: throw VaultApiException("GitHub не вернул sha коммита")

        val refPayload = buildJsonObject {
            put("ref", JsonPrimitive("refs/heads/$name"))
            put("sha", JsonPrimitive(commitSha))
        }
        try {
            request("POST", "$api/git/refs", refPayload.toString())
        } catch (e: VaultApiException) {
            if (e.message?.contains("422") == true) throw VaultApiException("Ветка «$name» уже существует")
            throw e
        }
    }

    private fun createBlob(bytes: ByteArray): String? {
        val payload = buildJsonObject {
            put("content", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
            put("encoding", JsonPrimitive("base64"))
        }
        val body = request("POST", "$api/git/blobs", payload.toString()) ?: return null
        return json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
    }

    private fun headSha(branch: String): String? = runCatching {
        val body = request("GET", "$api/git/ref/heads/$branch") ?: return null
        json.parseToJsonElement(body).jsonObject["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
    }.getOrNull()

    // ---------------------------------------------------------------- files

    /**
     * Reads a file of a branch.
     *
     * The Contents API inlines base64 only for files up to 1 MB, so bigger blobs (FB2 books)
     * are fetched through the Git data API by their sha.
     */
    fun readFile(path: String, branch: String): RemoteFile? {
        val body = request("GET", "$api/contents/$path?ref=$branch") ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        val sha = obj["sha"]?.jsonPrimitive?.content.orEmpty()
        val encoding = obj["encoding"]?.jsonPrimitive?.content.orEmpty()
        val inline = obj["content"]?.jsonPrimitive?.content.orEmpty()

        if (encoding == "none" || inline.isEmpty()) {
            if (sha.isEmpty()) return null
            val blob = request("GET", "$api/git/blobs/$sha") ?: return null
            val blobObj = json.parseToJsonElement(blob).jsonObject
            val content = blobObj["content"]?.jsonPrimitive?.content.orEmpty()
            val decoded = Base64.getMimeDecoder().decode(content)
            return RemoteFile(decoded, sha)
        }

        val decoded = Base64.getMimeDecoder().decode(inline)
        return RemoteFile(decoded, sha)
    }

    fun writeFile(
        path: String,
        branch: String,
        content: ByteArray,
        message: String,
        sha: String?,
        encoding: String = "base64",
    ): String? {
        val payload = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("content", JsonPrimitive(Base64.getEncoder().encodeToString(content)))
            put("branch", JsonPrimitive(branch))
            if (sha != null) put("sha", JsonPrimitive(sha))
        }
        val response = try {
            request("PUT", "$api/contents/$path", payload.toString())
        } catch (e: VaultApiException) {
            if (e.message?.contains("409") == true || e.message?.contains("422") == true) {
                throw VaultConflictException()
            }
            throw e
        }
        return runCatching {
            json.parseToJsonElement(response ?: "").jsonObject["content"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.content
        }.getOrNull()
    }

    fun deleteFile(path: String, branch: String, message: String) {
        val current = readFile(path, branch) ?: return
        val payload = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("sha", JsonPrimitive(current.sha))
            put("branch", JsonPrimitive(branch))
        }
        runCatching { request("DELETE", "$api/contents/$path", payload.toString()) }
    }

    // ---------------------------------------------------------------- http

    private fun request(method: String, url: String, body: String? = null): String? {
        if (!configured) throw VaultApiException("Укажите токен, владельца и репозиторий в настройках")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MyBooks")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 404) {
                connection.disconnect()
                return null
            }
            if (code == 401) throw VaultApiException("GitHub отклонил токен (401)")
            if (code == 403) throw VaultApiException("GitHub запретил операцию (403): проверьте права Contents: Read and write")
            if (code >= 300) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw VaultApiException("GitHub ${code}: ${text.take(200)}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }.ifEmpty { null }
        } catch (e: IOException) {
            throw VaultApiException("Нет связи с GitHub: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private fun sanitize(value: String): String = value.filter { it.code in 0x21..0x7E }
}
