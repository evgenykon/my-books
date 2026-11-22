package io.github.evgenykon.mybooks.secret

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Secrets of the app: the GitHub token and the password of every vault.
 *
 * The file is encrypted with DPAPI of the current Windows user (CryptProtectData), so it can
 * only be read by this account on this machine; copying the file elsewhere yields nothing.
 */
@Serializable
data class VaultSecrets(
    val token: String = "",
    val owner: String = "evgenykon",
    val repo: String = "my-books",
    /** Vault name (branch) -> password. */
    val passwords: Map<String, String> = emptyMap(),
    /** Vault that was open last time; checked and synced on the next start. */
    val activeVault: String = "",
)

class WindowsSecrets(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun read(): VaultSecrets = runCatching {
        if (!file.exists()) return@runCatching VaultSecrets()
        val plain = Crypt32Util.cryptUnprotectData(file.readBytes())
        json.decodeFromString(VaultSecrets.serializer(), plain.toString(Charsets.UTF_8))
    }.getOrDefault(VaultSecrets())

    fun write(secrets: VaultSecrets) {
        file.parentFile?.mkdirs()
        val plain = json.encodeToString(VaultSecrets.serializer(), secrets).toByteArray(Charsets.UTF_8)
        file.writeBytes(Crypt32Util.cryptProtectData(plain))
    }

    fun saveToken(token: String, owner: String, repo: String) {
        write(read().copy(token = clean(token), owner = clean(owner), repo = clean(repo)))
    }

    fun savePassword(vault: String, password: String) {
        write(read().copy(passwords = read().passwords + (vault to password)))
    }

    fun saveActiveVault(vault: String) {
        write(read().copy(activeVault = clean(vault)))
    }

    fun forgetPassword(vault: String) {
        write(read().copy(passwords = read().passwords - vault))
    }

    /**
     * Keeps only printable ASCII: tokens pasted from notes or web pages often carry
     * non-breaking spaces and newlines that make GitHub reject the Authorization header.
     */
    private fun clean(value: String): String = value.filter { it.code in 0x21..0x7E }
}
