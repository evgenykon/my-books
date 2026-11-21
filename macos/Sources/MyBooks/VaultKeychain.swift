import Foundation
import Security

/// Secrets of the vault feature, kept in the login keychain.
///
/// One generic-password item per secret: the GitHub token under the account `token`, the vault
/// password under `password:<name>`. Passwords are saved on create/open and never asked again.
enum VaultKeychain {
    static let service = "io.github.evgenykon.mybooks.vault"

    private static let tokenAccount = "token"

    // MARK: - Token

    static var token: String? { read(tokenAccount) }
    static var hasToken: Bool { (token?.isEmpty == false) }

    static func saveToken(_ token: String) {
        let cleaned = token.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            delete(tokenAccount)
        } else {
            write(cleaned, account: tokenAccount)
        }
    }

    static func forgetToken() {
        delete(tokenAccount)
    }

    // MARK: - Vault passwords

    static func password(for name: String) -> String? {
        read(passwordAccount(name))
    }

    static func hasPassword(for name: String) -> Bool {
        (password(for: name)?.isEmpty == false)
    }

    static func savePassword(_ password: String, for name: String) {
        guard !password.isEmpty else { return }
        write(password, account: passwordAccount(name))
    }

    static func forgetPassword(for name: String) {
        delete(passwordAccount(name))
    }

    private static func passwordAccount(_ name: String) -> String { "password:\(name)" }

    // MARK: - Keychain primitives

    private static func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func read(_ account: String) -> String? {
        var request = query(account: account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8)
        else {
            return nil
        }
        return value
    }

    private static func write(_ value: String, account: String) {
        let data = Data(value.utf8)
        let attributes = [kSecValueData as String: data]
        let status = SecItemUpdate(query(account: account) as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return }

        var item = query(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        item[kSecAttrLabel as String] = "My Books — \(account)"
        _ = SecItemAdd(item as CFDictionary, nil)
    }

    private static func delete(_ account: String) {
        _ = SecItemDelete(query(account: account) as CFDictionary)
    }
}

/// Non-secret vault settings: the repository the vaults live in.
enum VaultPreferences {
    private static let ownerKey = "vault.owner"
    private static let repoKey = "vault.repo"

    static var owner: String {
        get { UserDefaults.standard.string(forKey: ownerKey) ?? "evgenykon" }
        set { UserDefaults.standard.set(newValue, forKey: ownerKey) }
    }

    static var repo: String {
        get { UserDefaults.standard.string(forKey: repoKey) ?? "my-books" }
        set { UserDefaults.standard.set(newValue, forKey: repoKey) }
    }
}
