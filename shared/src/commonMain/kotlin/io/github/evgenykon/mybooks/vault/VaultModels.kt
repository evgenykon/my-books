package io.github.evgenykon.mybooks.vault

import kotlinx.serialization.Serializable

/**
 * Encrypted envelope written to a vault branch.
 *
 * The layout is the same as in the auth-app: a JSON header with the KDF parameters, the
 * cipher name and the nonce next to the base64 ciphertext, so the file can be inspected
 * with `cat` without revealing anything but the algorithm.
 */
@Serializable
data class VaultEnvelope(
    val version: Int = 1,
    val kdf: String = KDF_NAME,
    val iterations: Int = DEFAULT_ITERATIONS,
    val salt: String,
    val cipher: String = CIPHER_NAME,
    val nonce: String,
    val ciphertext: String,
)

/** Book as it travels inside a vault: metadata plus the name of its encrypted blob. */
@Serializable
data class VaultBook(
    val id: String,
    val title: String,
    val author: String,
    val fileName: String,
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    val percent: Int = 0,
    val totalChars: Int = 0,
    val addedAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val blockCount: Int = 0,
    val hash: String = "",
) {
    /** Path of the encrypted FB2 file inside the vault branch. */
    val blobPath: String get() = "$BOOKS_DIR/$id.enc"
}

@Serializable
data class VaultNote(
    val id: String,
    val bookId: String,
    val blockIndex: Int,
    val quote: String,
    val text: String,
    val createdAt: Long = 0L,
)

/** Reader appearance carried by the vault, so another device opens the book the same way. */
@Serializable
data class VaultSettings(
    val fontFamily: Int = 0,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.45f,
    val marginH: Int = 36,
    val marginV: Int = 28,
    val theme: Int = 0,
    val justified: Boolean = true,
)

/** Everything a vault stores; encrypted as `vault.enc` at the branch root. */
@Serializable
data class VaultManifest(
    val version: Int = 1,
    val name: String = "",
    val updatedAt: Long = 0L,
    val books: List<VaultBook> = emptyList(),
    val notes: List<VaultNote> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
)

/** A vault as it is shown in the UI. */
data class VaultInfo(
    val name: String,
    val books: Int = 0,
    val notes: Int = 0,
    val updatedAt: Long = 0L,
    val passwordSaved: Boolean = false,
)

const val KDF_NAME = "PBKDF2-HMAC-SHA256"
const val CIPHER_NAME = "AES-256-GCM"
const val DEFAULT_ITERATIONS = 200_000
const val VAULT_FILE = "vault.enc"
const val BOOKS_DIR = "books"

/**
 * Rules for names of vaults. A vault name is also its git branch name, so only lower-case
 * latin letters, digits and dashes are allowed, and a few names are reserved by the repo.
 */
object VaultNames {
    private val pattern = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    private val reserved = setOf(
        "main", "master", "head", "dev", "develop", "test", "release",
        "android", "macos", "windows", "core", "shared", "docs", "ci",
    )

    /** Returns null when the name is fine, otherwise a human readable reason. */
    fun validate(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Введите имя ветки"
        if (!pattern.matches(name)) {
            return "Только строчные латинские буквы, цифры и «-» (до 40 символов), начинаться с буквы или цифры"
        }
        if (name in reserved) return "Имя «$name» зарезервировано"
        if (name.endsWith("-")) return "Имя не может заканчиваться на «-»"
        if (name.contains("--")) return "Двойной дефис «--» не допускается"
        return null
    }

    fun isValid(raw: String): Boolean = validate(raw) == null
}
