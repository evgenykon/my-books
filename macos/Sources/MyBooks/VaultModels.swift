import Foundation

/// Constants shared by the vault format. They must match the Kotlin side
/// (`shared/.../vault/VaultModels.kt`), otherwise the same branch will not open on other devices.
enum VaultConst {
    static let vaultFile = "vault.enc"
    static let booksDir = "books"
    static let kdfName = "PBKDF2-HMAC-SHA256"
    static let cipherName = "AES-256-GCM"
    static let iterations = 200_000
    static let saltLength = 16
    static let nonceLength = 12
    static let keyLength = 32
    /// AES-GCM authentication tag, appended to the ciphertext as Java/Kotlin does.
    static let tagLength = 16
}

/// JSON header of an encrypted file: parameters of the KDF plus nonce and ciphertext.
struct VaultEnvelope: Codable {
    var version: Int = 1
    var kdf: String = VaultConst.kdfName
    var iterations: Int = VaultConst.iterations
    var salt: String
    var cipher: String = VaultConst.cipherName
    var nonce: String
    var ciphertext: String
}

struct VaultBook: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var author: String
    var fileName: String
    var blockIndex: Int
    var charOffset: Int
    var percent: Int
    var totalChars: Int
    /// Milliseconds since epoch, as `Long` on the Kotlin side.
    var addedAt: Int64
    var lastOpenedAt: Int64
    var blockCount: Int
    /// SHA-256 of the FB2 file; the same value as `BookRecord.fingerprint`.
    var hash: String

    /// Path of the encrypted FB2 file inside the vault branch.
    var blobPath: String { "\(VaultConst.booksDir)/\(id).enc" }
}

struct VaultNote: Codable, Identifiable, Hashable {
    var id: String
    var bookId: String
    var blockIndex: Int
    var quote: String
    var text: String
    var createdAt: Int64
}

/// Reader appearance carried by the vault, so another device opens the book the same way.
struct VaultSettings: Codable, Equatable {
    var fontFamily: Int = 0
    var fontSize: Int = 19
    var lineHeight: Double = 1.45
    var marginH: Int = 36
    var marginV: Int = 28
    var theme: Int = 0
    var justified: Bool = true
}

/// Everything a vault stores; encrypted as `vault.enc` at the branch root.
struct VaultManifest: Codable {
    var version: Int = 1
    var name: String = ""
    var updatedAt: Int64 = 0
    var books: [VaultBook] = []
    var notes: [VaultNote] = []
    var settings: VaultSettings = VaultSettings()
}

/// A vault as it is shown in the list.
struct VaultInfo: Identifiable, Hashable {
    var name: String
    var books: Int = 0
    var notes: Int = 0
    var updatedAt: Int64 = 0
    var passwordSaved: Bool = false

    var id: String { name }
}

// MARK: - Tolerant decoding
//
// A vault written by another platform may be one version ahead: missing fields fall back to
// defaults, unknown fields are ignored by JSONDecoder, so the app never loses a whole library
// over a single absent key.

extension KeyedDecodingContainerProtocol {
    func vaultString(_ key: Key, _ fallback: String = "") -> String {
        (try? decodeIfPresent(String.self, forKey: key)) ?? fallback
    }

    func vaultInt(_ key: Key, _ fallback: Int = 0) -> Int {
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return value }
        // Kotlin may serialise an Int as `19.0` if the field was ever a Float.
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int(value.rounded()) }
        return fallback
    }

    func vaultLong(_ key: Key, _ fallback: Int64 = 0) -> Int64 {
        if let value = try? decodeIfPresent(Int64.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int64(value.rounded()) }
        return fallback
    }

    func vaultDouble(_ key: Key, _ fallback: Double) -> Double {
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return Double(value) }
        return fallback
    }

    func vaultBool(_ key: Key, _ fallback: Bool) -> Bool {
        (try? decodeIfPresent(Bool.self, forKey: key)) ?? fallback
    }
}

extension VaultBook {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: c.vaultString(.id),
            title: c.vaultString(.title),
            author: c.vaultString(.author),
            fileName: c.vaultString(.fileName),
            blockIndex: c.vaultInt(.blockIndex),
            charOffset: c.vaultInt(.charOffset),
            percent: c.vaultInt(.percent),
            totalChars: c.vaultInt(.totalChars),
            addedAt: c.vaultLong(.addedAt),
            lastOpenedAt: c.vaultLong(.lastOpenedAt),
            blockCount: c.vaultInt(.blockCount),
            hash: c.vaultString(.hash)
        )
    }
}

extension VaultNote {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: c.vaultString(.id),
            bookId: c.vaultString(.bookId),
            blockIndex: c.vaultInt(.blockIndex),
            quote: c.vaultString(.quote),
            text: c.vaultString(.text),
            createdAt: c.vaultLong(.createdAt)
        )
    }
}

extension VaultSettings {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            fontFamily: c.vaultInt(.fontFamily, 0),
            fontSize: c.vaultInt(.fontSize, 19),
            lineHeight: c.vaultDouble(.lineHeight, 1.45),
            marginH: c.vaultInt(.marginH, 36),
            marginV: c.vaultInt(.marginV, 28),
            theme: c.vaultInt(.theme, 0),
            justified: c.vaultBool(.justified, true)
        )
    }
}

extension VaultManifest {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            version: c.vaultInt(.version, 1),
            name: c.vaultString(.name),
            updatedAt: c.vaultLong(.updatedAt),
            books: (try? c.decodeIfPresent([VaultBook].self, forKey: .books)) ?? [],
            notes: (try? c.decodeIfPresent([VaultNote].self, forKey: .notes)) ?? [],
            settings: (try? c.decodeIfPresent(VaultSettings.self, forKey: .settings)) ?? VaultSettings()
        )
    }
}

// MARK: - Names

/// Rules for vault names. A vault name is also its git branch name, so only lower-case latin
/// letters, digits and dashes are allowed, and a few names are reserved by the repository.
enum VaultNames {
    static let reserved: Set<String> = [
        "main", "master", "head", "dev", "develop", "test", "release",
        "android", "macos", "windows", "core", "shared", "docs", "ci",
    ]

    private static let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-")
    private static let leading = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789")

    /// Returns `nil` when the name is fine, otherwise a human readable reason.
    static func validate(_ raw: String) -> String? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return "Введите имя ветки" }
        if name.count > 40 || !name.unicodeScalars.allSatisfy({ allowed.contains($0) }) {
            return "Только строчные латинские буквы, цифры и «-» (до 40 символов), начинаться с буквы или цифры"
        }
        guard let first = name.unicodeScalars.first, leading.contains(first) else {
            return "Только строчные латинские буквы, цифры и «-» (до 40 символов), начинаться с буквы или цифры"
        }
        if reserved.contains(name) { return "Имя «\(name)» зарезервировано" }
        if name.hasSuffix("-") { return "Имя не может заканчиваться на «-»" }
        if name.contains("--") { return "Двойной дефис «--» не допускается" }
        return nil
    }

    static func isValid(_ raw: String) -> Bool { validate(raw) == nil }
}
