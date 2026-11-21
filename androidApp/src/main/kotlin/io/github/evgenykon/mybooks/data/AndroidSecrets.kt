package io.github.evgenykon.mybooks.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * GitHub token and vault passwords of the Android client.
 *
 * Stored in EncryptedSharedPreferences, i.e. keys live in the Android Keystore, so the
 * values are unreadable even from a rooted device's file dump.
 */
class AndroidSecrets(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mybooks_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val passwordsSerializer = MapSerializer(String.serializer(), String.serializer())

    var token: String
        get() = clean(prefs.getString(KEY_TOKEN, "").orEmpty())
        set(value) = prefs.edit().putString(KEY_TOKEN, clean(value)).apply()

    var owner: String
        get() = clean(prefs.getString(KEY_OWNER, "evgenykon").orEmpty())
        set(value) = prefs.edit().putString(KEY_OWNER, clean(value)).apply()

    var repo: String
        get() = clean(prefs.getString(KEY_REPO, "my-books").orEmpty())
        set(value) = prefs.edit().putString(KEY_REPO, clean(value)).apply()

    fun isConfigured(): Boolean = token.isNotEmpty() && owner.isNotEmpty() && repo.isNotEmpty()

    fun password(vault: String): String? = passwords()[vault]

    fun savePassword(vault: String, password: String) {
        prefs.edit().putString(KEY_PASSWORDS, json.encodeToString(passwordsSerializer, passwords() + (vault to password)))
            .apply()
    }

    fun forgetPassword(vault: String) {
        prefs.edit().putString(KEY_PASSWORDS, json.encodeToString(passwordsSerializer, passwords() - vault)).apply()
    }

    private fun passwords(): Map<String, String> = runCatching {
        json.decodeFromString(passwordsSerializer, prefs.getString(KEY_PASSWORDS, "{}").orEmpty())
    }.getOrDefault(emptyMap())

    /** Strips whitespace and non-ASCII characters picked up when pasting a token. */
    private fun clean(value: String): String = value.filter { it.code in 0x21..0x7E }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_PASSWORDS = "vault_passwords"
    }
}
