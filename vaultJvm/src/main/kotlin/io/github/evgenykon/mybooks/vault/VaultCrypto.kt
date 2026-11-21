package io.github.evgenykon.mybooks.vault

import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password based encryption of a vault: PBKDF2-HMAC-SHA256 (200 000 iterations) derives a
 * 256-bit key, AES-256-GCM seals the payload. Salt and nonce are random for every save, so
 * two writes of the same data never produce the same file.
 *
 * The macOS client implements the same format with CryptoKit/CommonCrypto.
 */
object VaultCrypto {

    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val random = SecureRandom()

    /** Encrypts the payload and returns the JSON envelope as bytes. */
    fun encrypt(
        plain: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }

        val key = deriveKey(password, salt, iterations)
        val ciphertext = gcm(Cipher.ENCRYPT_MODE, key, nonce, plain)

        val envelope = VaultEnvelope(
            iterations = iterations,
            salt = encode(salt),
            nonce = encode(nonce),
            ciphertext = encode(ciphertext),
        )
        return json.encodeToString(VaultEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** Decrypts an envelope produced by [encrypt]; throws when the password is wrong. */
    fun decrypt(envelopeBytes: ByteArray, password: CharArray): ByteArray {
        val envelope = json.decodeFromString(
            VaultEnvelope.serializer(),
            envelopeBytes.toString(Charsets.UTF_8),
        )
        require(envelope.cipher == CIPHER_NAME) { "Unsupported cipher: ${envelope.cipher}" }
        val salt = decode(envelope.salt)
        val nonce = decode(envelope.nonce)
        val ciphertext = decode(envelope.ciphertext)
        val key = deriveKey(password, salt, envelope.iterations)
        return gcm(Cipher.DECRYPT_MODE, key, nonce, ciphertext)
    }

    /** Number of KDF iterations of an envelope, without decrypting it. */
    fun iterationsOf(envelopeBytes: ByteArray): Int = runCatching {
        json.decodeFromString(VaultEnvelope.serializer(), envelopeBytes.toString(Charsets.UTF_8)).iterations
    }.getOrDefault(DEFAULT_ITERATIONS)

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val key = factory.generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun gcm(mode: Int, key: SecretKeySpec, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(data)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
