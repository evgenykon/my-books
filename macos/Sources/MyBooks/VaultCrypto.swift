import Foundation
import CryptoKit
import Security

#if canImport(CommonCrypto)
import CommonCrypto
#endif

enum VaultCryptoError: LocalizedError {
    case malformedEnvelope
    case unsupportedCipher(String)
    case wrongPassword
    case cryptoFailure

    var errorDescription: String? {
        switch self {
        case .malformedEnvelope:
            return "Файл хранилища повреждён: не удалось прочитать заголовок"
        case .unsupportedCipher(let name):
            return "Неподдерживаемый шифр хранилища: \(name)"
        case .wrongPassword:
            return "Неверный пароль или файл хранилища повреждён"
        case .cryptoFailure:
            return "Не удалось зашифровать данные хранилища"
        }
    }
}

/// Password based encryption of a vault, byte-compatible with the Kotlin implementation:
/// PBKDF2-HMAC-SHA256 (200 000 iterations, 16-byte salt) derives a 256-bit key, AES-256-GCM
/// seals the payload and the 16-byte tag is appended to the ciphertext. Salt and nonce are
/// random on every save, so the same data never produces the same file twice.
enum VaultCrypto {

    // MARK: - Public API

    static func encrypt(_ plain: Data, password: String, iterations: Int = VaultConst.iterations) throws -> Data {
        let salt = randomBytes(VaultConst.saltLength)
        let nonceData = randomBytes(VaultConst.nonceLength)
        let key = deriveKey(password: password, salt: salt, iterations: iterations)

        guard let nonce = try? AES.GCM.Nonce(data: nonceData) else {
            throw VaultCryptoError.cryptoFailure
        }
        let sealed: AES.GCM.SealedBox
        do {
            sealed = try AES.GCM.seal(plain, using: key, nonce: nonce)
        } catch {
            throw VaultCryptoError.cryptoFailure
        }

        var ciphertext = sealed.ciphertext
        ciphertext.append(sealed.tag)

        // Built by hand to keep the key order of the Kotlin envelope: `cat vault.enc` on any
        // platform shows the same header.
        let json = "{\"version\":1,\"kdf\":\"\(VaultConst.kdfName)\",\"iterations\":\(iterations),"
            + "\"salt\":\"\(salt.base64EncodedString())\",\"cipher\":\"\(VaultConst.cipherName)\","
            + "\"nonce\":\"\(nonceData.base64EncodedString())\","
            + "\"ciphertext\":\"\(ciphertext.base64EncodedString())\"}"
        return Data(json.utf8)
    }

    /// Decrypts an envelope; a wrong password fails the GCM authentication check.
    static func decrypt(_ envelopeData: Data, password: String) throws -> Data {
        guard let envelope = try? JSONDecoder().decode(VaultEnvelope.self, from: envelopeData) else {
            throw VaultCryptoError.malformedEnvelope
        }
        guard envelope.cipher == VaultConst.cipherName else {
            throw VaultCryptoError.unsupportedCipher(envelope.cipher)
        }
        guard let salt = Data(base64Encoded: envelope.salt),
              let nonceData = Data(base64Encoded: envelope.nonce),
              let payload = Data(base64Encoded: envelope.ciphertext),
              payload.count > VaultConst.tagLength
        else {
            throw VaultCryptoError.malformedEnvelope
        }

        let key = deriveKey(password: password, salt: salt, iterations: envelope.iterations)
        guard let nonce = try? AES.GCM.Nonce(data: nonceData) else {
            throw VaultCryptoError.malformedEnvelope
        }
        let ciphertext = payload.dropLast(VaultConst.tagLength)
        let tag = payload.suffix(VaultConst.tagLength)

        do {
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(box, using: key)
        } catch {
            // Authentication failure means either a wrong password or a tampered file.
            throw VaultCryptoError.wrongPassword
        }
    }

    /// Number of KDF iterations of an envelope, without decrypting it.
    static func iterations(of envelopeData: Data) -> Int {
        (try? JSONDecoder().decode(VaultEnvelope.self, from: envelopeData))?.iterations ?? VaultConst.iterations
    }

    // MARK: - Key derivation

    private static func deriveKey(password: String, salt: Data, iterations: Int) -> SymmetricKey {
        let bytes = pbkdf2(
            password: Array(password.utf8),
            salt: Array(salt),
            iterations: max(iterations, 1),
            keyLength: VaultConst.keyLength
        )
        return SymmetricKey(data: Data(bytes))
    }

    private static func pbkdf2(password: [UInt8], salt: [UInt8], iterations: Int, keyLength: Int) -> [UInt8] {
        #if canImport(CommonCrypto)
        if !password.isEmpty {
            var derived = [UInt8](repeating: 0, count: keyLength)
            let status: Int32 = derived.withUnsafeMutableBufferPointer { output in
                password.withUnsafeBufferPointer { pass in
                    salt.withUnsafeBufferPointer { saltPtr in
                        let passPtr = UnsafeRawPointer(pass.baseAddress!).assumingMemoryBound(to: Int8.self)
                        return CCKeyDerivationPBKDF(
                            CCPBKDFAlgorithm(kCCPBKDF2),
                            passPtr,
                            pass.count,
                            saltPtr.baseAddress,
                            salt.count,
                            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                            UInt32(iterations),
                            output.baseAddress,
                            keyLength
                        )
                    }
                }
            }
            if status == 0 { return derived } // 0 == kCCSuccess
        }
        #endif
        // Fallback (and the path used if the CommonCrypto module is unavailable).
        return pbkdf2WithCryptoKit(password: password, salt: salt, iterations: iterations, keyLength: keyLength)
    }

    /// RFC 2898 PBKDF2 on top of CryptoKit HMAC-SHA256.
    private static func pbkdf2WithCryptoKit(
        password: [UInt8],
        salt: [UInt8],
        iterations: Int,
        keyLength: Int
    ) -> [UInt8] {
        let key = SymmetricKey(data: Data(password))
        var derived = [UInt8]()
        derived.reserveCapacity(keyLength + 32)
        var block: UInt32 = 1
        while derived.count < keyLength {
            var message = salt
            var bigEndian = block.bigEndian
            withUnsafeBytes(of: &bigEndian) { message.append(contentsOf: $0) }
            var u = Array(HMAC<SHA256>.authenticationCode(for: Data(message), using: key))
            var accumulator = u
            if iterations > 1 {
                for _ in 1..<iterations {
                    u = Array(HMAC<SHA256>.authenticationCode(for: Data(u), using: key))
                    for index in accumulator.indices { accumulator[index] ^= u[index] }
                }
            }
            derived.append(contentsOf: accumulator)
            block += 1
        }
        return Array(derived.prefix(keyLength))
    }

    // MARK: - Helpers

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if status != errSecSuccess {
            bytes = (0..<count).map { _ in UInt8.random(in: UInt8.min...UInt8.max) }
        }
        return Data(bytes)
    }
}
