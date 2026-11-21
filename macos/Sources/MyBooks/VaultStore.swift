import Foundation
import Combine

/// Encrypted vaults stored in git branches of a GitHub repository.
///
/// A vault holds the whole library: books (one encrypted blob each), notes, reading progress
/// and reader settings. Opening a vault downloads it into the local library; every change of
/// the library is pushed to the active vault shortly after it happens.
@MainActor
final class VaultStore: ObservableObject {
    @Published private(set) var configured = false
    @Published private(set) var owner: String = "evgenykon"
    @Published private(set) var repo: String = "my-books"
    @Published private(set) var tokenSaved = false
    @Published private(set) var vaults: [VaultInfo] = []
    @Published private(set) var active: String?
    @Published private(set) var busy = false
    @Published private(set) var busyText: String?
    @Published var message: String?
    @Published var error: String?
    /// Name of a vault that needs its password typed in (the keychain has none for it).
    @Published var passwordPrompt: String?

    private let library: LibraryStore
    private var password: String?
    private var syncTask: Task<Void, Never>?
    /// Reused between operations: one URLSession per request would leak connection pools.
    private var cachedClient: VaultGitHubClient?

    init(library: LibraryStore) {
        self.library = library
        let savedOwner = VaultPreferences.owner
        let savedRepo = VaultPreferences.repo
        let savedToken = VaultKeychain.hasToken
        owner = savedOwner
        repo = savedRepo
        tokenSaved = savedToken
        configured = savedToken && !savedOwner.isEmpty && !savedRepo.isEmpty
    }

    private var github: VaultGitHubClient {
        if let cachedClient { return cachedClient }
        let client = VaultGitHubClient(token: VaultKeychain.token ?? "", owner: owner, repo: repo)
        cachedClient = client
        return client
    }

    private func repository() -> VaultRepository {
        VaultRepository(client: github)
    }

    private func begin(_ text: String) {
        busy = true
        busyText = text
        error = nil
        message = nil
    }

    private func finish() {
        busy = false
        busyText = nil
    }

    // MARK: - Settings

    func saveSettings(token: String, owner newOwner: String, repo newRepo: String) {
        let cleanToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanOwner = newOwner.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanRepo = newRepo.trimmingCharacters(in: .whitespacesAndNewlines)

        if !cleanToken.isEmpty { VaultKeychain.saveToken(cleanToken) }
        if !cleanOwner.isEmpty {
            VaultPreferences.owner = cleanOwner
            owner = cleanOwner
        }
        if !cleanRepo.isEmpty {
            VaultPreferences.repo = cleanRepo
            repo = cleanRepo
        }

        cachedClient = nil
        tokenSaved = VaultKeychain.hasToken
        configured = tokenSaved && !owner.isEmpty && !repo.isEmpty
        error = configured ? nil : "Нужен токен GitHub с правом Contents: Read and write"
        message = configured ? "Настройки GitHub сохранены" : nil
        if configured { refreshVaults() }
    }

    // MARK: - Vault list

    func refreshVaults() {
        guard configured else { return }
        Task { await self.loadVaults() }
    }

    private func loadVaults() async {
        guard !busy else { return }
        begin("Читаю ветки…")
        defer { finish() }
        let repository = self.repository()
        do {
            let names = try await repository.listVaults()
            var infos: [VaultInfo] = []
            for name in names {
                var info = VaultInfo(name: name)
                info.passwordSaved = VaultKeychain.hasPassword(for: name)
                // Counts are known only for vaults whose password is saved: the manifest is encrypted.
                if let saved = VaultKeychain.password(for: name),
                   let manifest = try? await repository.openVault(name: name, password: saved).manifest {
                    info.books = manifest.books.count
                    info.notes = manifest.notes.count
                    info.updatedAt = manifest.updatedAt
                }
                infos.append(info)
            }
            vaults = infos.sorted { $0.name < $1.name }
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Create / open / close / delete

    func createVault(name rawName: String, password: String, confirmation: String) {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            self.begin("Создаю ветку «\(name)»…")
            do {
                self.message = try await self.createWork(name: name, password: password, confirmation: confirmation)
            } catch {
                self.error = error.localizedDescription
            }
            self.finish()
        }
    }

    private func createWork(name: String, password: String, confirmation: String) async throws -> String {
        if let reason = VaultNames.validate(name) { throw VaultError.message(reason) }
        if password.count < 6 { throw VaultError.message("Пароль должен быть не короче 6 символов") }
        if password != confirmation { throw VaultError.message("Пароли не совпадают") }
        if vaults.contains(where: { $0.name == name }) {
            throw VaultError.message("Ветка «\(name)» уже существует")
        }

        let repository = self.repository()
        var manifest = library.vaultManifest(named: name)
        var blobs: [String: Data] = [:]
        for book in manifest.books {
            if let data = library.bookData(id: book.id) { blobs[book.id] = data }
        }
        manifest = try await repository.createVault(
            name: name,
            password: password,
            manifest: manifest,
            books: blobs
        )

        VaultKeychain.savePassword(password, for: name)
        self.password = password
        active = name
        await reloadAfterChange()
        return "Хранилище «\(name)» создано: \(manifest.books.count) книг, \(manifest.notes.count) заметок"
    }

    /// Opens a vault. Without a password the saved one is used; the caller asks for it when
    /// `passwordSaved` is false.
    func openVault(name: String, password: String? = nil) {
        let typed = password?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let resolved = typed.isEmpty ? VaultKeychain.password(for: name) : typed
        guard let secret = resolved, !secret.isEmpty else {
            error = "Введите пароль хранилища «\(name)»"
            passwordPrompt = name
            return
        }
        Task {
            self.begin("Открываю «\(name)»…")
            do {
                self.message = try await self.openWork(name: name, password: secret)
            } catch {
                self.error = error.localizedDescription
            }
            self.finish()
        }
    }

    private func openWork(name: String, password: String) async throws -> String {
        let repository = self.repository()
        let snapshot = try await repository.openVault(name: name, password: password)

        // Merge the vault into the local library: missing books are written to disk, notes and
        // settings come from the vault (it is the shared copy). Local extra books stay.
        var blobs: [String: Data] = [:]
        for book in snapshot.manifest.books where !library.hasBook(id: book.id, hash: book.hash) {
            // A single unreadable blob must not abort opening the whole vault.
            if let data = try? await repository.readBook(name: name, password: password, book: book) {
                blobs[book.id] = data
            }
        }
        library.applyVault(
            books: snapshot.manifest.books,
            notes: snapshot.manifest.notes,
            settings: snapshot.manifest.settings,
            blobs: blobs
        )

        VaultKeychain.savePassword(password, for: name)
        self.password = password
        active = name
        await reloadAfterChange()
        return "Хранилище «\(name)» открыто: \(snapshot.manifest.books.count) книг, "
            + "\(snapshot.manifest.notes.count) заметок"
    }

    func closeVault() {
        syncTask?.cancel()
        password = nil
        active = nil
        message = "Хранилище закрыто"
    }

    func deleteVault(name: String) {
        Task {
            self.begin("Удаляю ветку «\(name)»…")
            do {
                try await self.repository().deleteVault(name)
                VaultKeychain.forgetPassword(for: name)
                if self.active == name { self.closeVault() }
                await self.reloadAfterChange()
                self.message = "Ветка «\(name)» удалена"
            } catch {
                self.error = error.localizedDescription
            }
            self.finish()
        }
    }

    private func reloadAfterChange() async {
        let names = (try? await repository().listVaults()) ?? vaults.map(\.name)
        var infos: [VaultInfo] = []
        for name in names {
            var info = VaultInfo(name: name)
            info.passwordSaved = VaultKeychain.hasPassword(for: name)
            if let saved = VaultKeychain.password(for: name),
               let manifest = try? await repository().openVault(name: name, password: saved).manifest {
                info.books = manifest.books.count
                info.notes = manifest.notes.count
                info.updatedAt = manifest.updatedAt
            }
            infos.append(info)
        }
        vaults = infos.sorted { $0.name < $1.name }
    }

    // MARK: - Sync

    /// Pushes the library to the active vault a second and a half after the last change.
    func scheduleSync() {
        guard active != nil, password != nil else { return }
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await self?.push()
        }
    }

    func syncNow() {
        syncTask?.cancel()
        Task { await self.push() }
    }

    private func push() async {
        guard let vault = active, let password = password else { return }
        guard !busy else {
            // A long operation is running; try again when it ends.
            scheduleSync()
            return
        }

        begin("Синхронизирую «\(vault)»…")
        do {
            let repository = self.repository()
            let local = library.vaultManifest(named: vault)
            let remote = try await repository.openVault(name: vault, password: password).manifest

            var remoteById: [String: VaultBook] = [:]
            for book in remote.books { remoteById[book.id] = book }

            var changed: [String: Data] = [:]
            for book in local.books {
                if let known = remoteById[book.id], known.hash == book.hash, !book.hash.isEmpty {
                    continue
                }
                if let data = library.bookData(id: book.id) { changed[book.id] = data }
            }
            let removed = remote.books.map(\.id).filter { id in
                !local.books.contains { $0.id == id }
            }

            let saved = try await repository.saveVault(
                name: vault,
                password: password,
                manifest: local,
                changedBooks: changed,
                removedBooks: removed
            )
            vaults = vaults.map { info -> VaultInfo in
                guard info.name == vault else { return info }
                var updated = info
                updated.books = saved.books.count
                updated.notes = saved.notes.count
                updated.updatedAt = saved.updatedAt
                updated.passwordSaved = true
                return updated
            }
            message = "Синхронизировано: \(saved.books.count) книг, \(saved.notes.count) заметок"
        } catch {
            self.error = "Синхронизация не удалась: \(error.localizedDescription)"
        }
        finish()
    }
}
