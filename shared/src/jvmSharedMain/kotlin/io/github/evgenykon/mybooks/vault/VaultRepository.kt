package io.github.evgenykon.mybooks.vault

import kotlinx.serialization.json.Json

/** Decrypted vault content read from a branch. */
data class VaultSnapshot(val manifest: VaultManifest)

/**
 * Reads and writes encrypted vaults stored in git branches of a GitHub repository.
 *
 * A vault is one branch: `vault.enc` holds the encrypted manifest (books metadata, notes,
 * reading progress, reader settings) and `books/<id>.enc` holds every FB2 file encrypted on
 * its own, so adding one book does not re-upload the whole library.
 */
class VaultRepository(private val client: GitHubVaultClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configured: Boolean get() = client.configured

    fun listVaults(): List<String> = client.listVaults()

    fun vaultExists(name: String): Boolean = client.hasVault(name)

    fun deleteVault(name: String) {
        client.deleteBranch(name)
    }

    /** Creates a branch and stores the first encrypted manifest with its book blobs. */
    fun createVault(
        name: String,
        password: CharArray,
        manifest: VaultManifest,
        books: Map<String, ByteArray>,
    ): VaultManifest {
        check(!client.hasVault(name)) { "Ветка «$name» уже содержит хранилище" }

        // One commit with every file: the branch starts empty and holds nothing but the vault.
        val stamped = manifest.copy(name = name, updatedAt = System.currentTimeMillis())
        val files = linkedMapOf<String, ByteArray>()
        for ((id, bytes) in books) files["$BOOKS_DIR/$id.enc"] = seal(bytes, password)
        files[VAULT_FILE] = sealManifest(stamped, password)
        client.createOrphanBranch(name, files, "Create vault $name")
        return stamped
    }

    fun openVault(name: String, password: CharArray): VaultSnapshot {
        val file = client.readFile(VAULT_FILE, name)
            ?: throw VaultApiException("В ветке «$name» нет хранилища")
        return VaultSnapshot(readManifest(file.content, password))
    }

    /** Decrypts one book of the vault. */
    fun readBook(name: String, password: CharArray, book: VaultBook): ByteArray? {
        val file = client.readFile(book.blobPath, name) ?: return null
        return VaultCrypto.decrypt(file.content, password)
    }

    /**
     * Writes the manifest and the changed/deleted book blobs.
     *
     * Retries a few times when the branch moved meanwhile (another device pushed).
     */
    fun saveVault(
        name: String,
        password: CharArray,
        manifest: VaultManifest,
        changedBooks: Map<String, ByteArray> = emptyMap(),
        removedBooks: List<String> = emptyList(),
    ): VaultManifest {
        var attempt = 0
        while (true) {
            try {
                for ((id, bytes) in changedBooks) {
                    val path = "$BOOKS_DIR/$id.enc"
                    val existing = client.readFile(path, name)
                    client.writeFile(path, name, seal(bytes, password), "Update book $id", existing?.sha)
                }
                for (id in removedBooks) {
                    client.deleteFile("$BOOKS_DIR/$id.enc", name, "Remove book $id")
                }

                val stamped = manifest.copy(name = name, updatedAt = System.currentTimeMillis())
                val current = client.readFile(VAULT_FILE, name)
                client.writeFile(
                    path = VAULT_FILE,
                    branch = name,
                    content = sealManifest(stamped, password),
                    message = "Update vault $name",
                    sha = current?.sha,
                )
                return stamped
            } catch (e: VaultConflictException) {
                attempt++
                if (attempt >= 3) throw VaultApiException("Не удалось обновить ветку «$name»: конфликт версий")
            }
        }
    }

    /** Verifies a password against an existing vault without keeping anything. */
    fun verifyPassword(name: String, password: CharArray): Boolean {
        val file = client.readFile(VAULT_FILE, name) ?: return false
        return runCatching { readManifest(file.content, password) }.isSuccess
    }

    private fun seal(bytes: ByteArray, password: CharArray): ByteArray =
        VaultCrypto.encrypt(bytes, password)

    private fun sealManifest(manifest: VaultManifest, password: CharArray): ByteArray =
        VaultCrypto.encrypt(
            json.encodeToString(VaultManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
            password,
        )

    private fun readManifest(bytes: ByteArray, password: CharArray): VaultManifest {
        val plain = VaultCrypto.decrypt(bytes, password)
        return json.decodeFromString(VaultManifest.serializer(), plain.toString(Charsets.UTF_8))
    }
}
