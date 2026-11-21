import Foundation

/// Decrypted vault content read from a branch.
struct VaultSnapshot {
    let manifest: VaultManifest
}

/// Reads and writes encrypted vaults stored in git branches.
///
/// A vault is one branch: `vault.enc` holds the encrypted manifest (books metadata, notes,
/// reading progress, reader settings) and `books/<id>.enc` holds every FB2 file encrypted on
/// its own, so adding one book does not upload the whole library again.
final class VaultRepository {
    private let client: VaultGitHubClient
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(client: VaultGitHubClient) {
        self.client = client
    }

    var configured: Bool { client.configured }

    func listVaults() async throws -> [String] {
        try await client.listVaults()
    }

    func vaultExists(_ name: String) async throws -> Bool {
        try await client.hasVault(name)
    }

    func deleteVault(_ name: String) async throws {
        try await client.deleteBranch(name)
    }

    /// Creates a branch and stores the first encrypted manifest with its book blobs.
    func createVault(
        name: String,
        password: String,
        manifest: VaultManifest,
        books: [String: Data]
    ) async throws -> VaultManifest {
        if try await client.hasVault(name) {
            throw VaultError.message("Ветка «\(name)» уже содержит хранилище")
        }
        try await client.createBranch(name)

        for (id, bytes) in books {
            let sealed = try VaultCrypto.encrypt(bytes, password: password)
            try await client.writeFile(
                path: "\(VaultConst.booksDir)/\(id).enc",
                branch: name,
                content: sealed,
                message: "Add book \(id)",
                sha: nil
            )
        }

        var stamped = manifest
        stamped.name = name
        stamped.updatedAt = Self.nowMillis()
        try await client.writeFile(
            path: VaultConst.vaultFile,
            branch: name,
            content: try sealManifest(stamped, password: password),
            message: "Create vault \(name)",
            sha: nil
        )
        return stamped
    }

    func openVault(name: String, password: String) async throws -> VaultSnapshot {
        guard let file = try await client.readFile(path: VaultConst.vaultFile, branch: name) else {
            throw VaultError.message("В ветке «\(name)» нет хранилища")
        }
        return VaultSnapshot(manifest: try readManifest(file.content, password: password))
    }

    /// Decrypts one book of the vault.
    func readBook(name: String, password: String, book: VaultBook) async throws -> Data? {
        guard let file = try await client.readFile(path: book.blobPath, branch: name) else {
            return nil
        }
        return try VaultCrypto.decrypt(file.content, password: password)
    }

    /// Writes the manifest and the changed or removed book blobs.
    ///
    /// Retries a few times when the branch moved meanwhile (another device pushed).
    func saveVault(
        name: String,
        password: String,
        manifest: VaultManifest,
        changedBooks: [String: Data] = [:],
        removedBooks: [String] = []
    ) async throws -> VaultManifest {
        var attempt = 0
        while true {
            do {
                for (id, bytes) in changedBooks {
                    let path = "\(VaultConst.booksDir)/\(id).enc"
                    let existing = try await client.readFile(path: path, branch: name)
                    try await client.writeFile(
                        path: path,
                        branch: name,
                        content: try VaultCrypto.encrypt(bytes, password: password),
                        message: "Update book \(id)",
                        sha: existing?.sha
                    )
                }
                for id in removedBooks {
                    try await client.deleteFile(
                        path: "\(VaultConst.booksDir)/\(id).enc",
                        branch: name,
                        message: "Remove book \(id)"
                    )
                }

                var stamped = manifest
                stamped.name = name
                stamped.updatedAt = Self.nowMillis()
                let current = try await client.readFile(path: VaultConst.vaultFile, branch: name)
                try await client.writeFile(
                    path: VaultConst.vaultFile,
                    branch: name,
                    content: try sealManifest(stamped, password: password),
                    message: "Update vault \(name)",
                    sha: current?.sha
                )
                return stamped
            } catch let error as VaultError where error == .conflict {
                attempt += 1
                if attempt >= 3 {
                    throw VaultError.message("Не удалось обновить ветку «\(name)»: конфликт версий")
                }
            }
        }
    }

    /// Verifies a password against an existing vault without keeping anything.
    func verify(password: String, for name: String) async throws -> Bool {
        guard let file = try await client.readFile(path: VaultConst.vaultFile, branch: name) else {
            return false
        }
        return (try? readManifest(file.content, password: password)) != nil
    }

    // MARK: - Private

    private func sealManifest(_ manifest: VaultManifest, password: String) throws -> Data {
        let plain = try encoder.encode(manifest)
        return try VaultCrypto.encrypt(plain, password: password)
    }

    private func readManifest(_ bytes: Data, password: String) throws -> VaultManifest {
        let plain = try VaultCrypto.decrypt(bytes, password: password)
        do {
            return try decoder.decode(VaultManifest.self, from: plain)
        } catch {
            throw VaultError.message("Хранилище расшифровано, но его содержимое не читается")
        }
    }

    static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
