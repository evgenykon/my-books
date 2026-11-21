import Foundation
import Combine
import CryptoKit
import Shared

struct BookRecord: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var author: String
    var fileName: String
    var blockIndex: Int = 0
    var charOffset: Int = 0
    var percent: Int = 0
    var blockCount: Int = 0
    var addedAt: Double = 0
    var lastOpenedAt: Double = 0
    /// Character count of the book, shared with other platforms through the vault.
    var totalChars: Int? = nil
    /// SHA-256 of the source file, used to keep the library free of duplicates.
    var fingerprint: String? = nil
}

struct ReaderNote: Codable, Identifiable, Hashable {
    var id: String
    var bookId: String
    var blockIndex: Int
    var quote: String
    var text: String
    var createdAt: Double = 0
}

struct ReaderSettings: Codable, Equatable {
    var fontFamily: Int = 0        // 0 serif, 1 sans, 2 mono
    var fontSize: Double = 17
    var lineHeight: Double = 1.55
    var marginH: Double = 64
    var marginV: Double = 40
    var theme: Int = 0             // 0 light, 1 sepia, 2 dark
    var justified: Bool = true
}

struct ReaderPalette {
    let background: String
    let text: String
    let secondary: String
    let accent: String

    static func of(_ settings: ReaderSettings) -> ReaderPalette {
        switch settings.theme {
        case 1:
            return ReaderPalette(background: "#F6EDDC", text: "#3B3229", secondary: "#8A7A63", accent: "#9A6B3F")
        case 2:
            return ReaderPalette(background: "#14161A", text: "#D8D8D6", secondary: "#8C8F95", accent: "#C8A46A")
        default:
            return ReaderPalette(background: "#FDFDFB", text: "#1A1A1A", secondary: "#7A7A7A", accent: "#8A6D3B")
        }
    }
}

extension String {
    /// `#RRGGBB` helper used by the palettes above.
    var nsColor: NSColorCompat { NSColorCompat(hex: self) }
}

#if canImport(AppKit)
import AppKit
typealias NSColorCompat = NSColor

extension NSColor {
    convenience init(hex: String) {
        let cleaned = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        let value = UInt32(cleaned, radix: 16) ?? 0
        self.init(
            srgbRed: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}
#endif

@MainActor
final class LibraryStore: ObservableObject {
    @Published var books: [BookRecord] = []
    @Published var notes: [ReaderNote] = []
    @Published var settings = ReaderSettings()
    @Published var lastError: String?

    /// Set by `MyBooksApp`: every library change is pushed to the active vault through it.
    weak var vault: VaultStore?

    private let root: URL
    private let booksFile: URL
    private let notesFile: URL
    private let settingsFile: URL
    /// Set while a vault snapshot is applied, so downloading a vault does not trigger a push back.
    private var syncSuspended = false

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        root = base.appendingPathComponent("MyBooks", isDirectory: true)
        booksFile = root.appendingPathComponent("books.json")
        notesFile = root.appendingPathComponent("notes.json")
        settingsFile = root.appendingPathComponent("reader-settings.json")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        load()
        deduplicateLibrary()
    }

    // MARK: - Persistence

    private func load() {
        let decoder = JSONDecoder()
        if let data = try? Data(contentsOf: booksFile),
           let list = try? decoder.decode([BookRecord].self, from: data) {
            books = list.sorted { $0.lastOpenedAt > $1.lastOpenedAt }
        }
        if let data = try? Data(contentsOf: notesFile),
           let list = try? decoder.decode([ReaderNote].self, from: data) {
            notes = list
        }
        if let data = try? Data(contentsOf: settingsFile),
           let value = try? decoder.decode(ReaderSettings.self, from: data) {
            settings = value
        }
    }

    private func saveBooks() {
        do {
            let data = try JSONEncoder().encode(books)
            try data.write(to: booksFile, options: .atomic)
        } catch {
            lastError = "Не удалось сохранить библиотеку: \(error.localizedDescription)"
        }
    }

    private func saveNotes() {
        do {
            let data = try JSONEncoder().encode(notes)
            try data.write(to: notesFile, options: .atomic)
        } catch {
            lastError = "Не удалось сохранить заметки: \(error.localizedDescription)"
        }
    }

    private func saveSettings() {
        do {
            let data = try JSONEncoder().encode(settings)
            try data.write(to: settingsFile, options: .atomic)
        } catch {
            lastError = "Не удалось сохранить настройки: \(error.localizedDescription)"
        }
    }

    /// Tells the vault manager that the library changed; it pushes to the active branch after
    /// a short debounce.
    private func changed() {
        guard !syncSuspended else { return }
        vault?.scheduleSync()
    }

    // MARK: - Books

    func fileURL(for record: BookRecord) -> URL {
        root.appendingPathComponent(record.fileName)
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func storedFingerprint(of record: BookRecord) -> String? {
        if let value = record.fingerprint, !value.isEmpty { return value }
        guard let data = try? Data(contentsOf: fileURL(for: record)) else { return nil }
        return Self.sha256(data)
    }

    /// Imports a book unless an identical file is already in the library.
    @discardableResult
    func importBook(at url: URL) -> BookRecord? {
        do {
            let data = try Data(contentsOf: url)
            let hash = Self.sha256(data)
            if let index = books.firstIndex(where: { storedFingerprint(of: $0) == hash }) {
                books[index].lastOpenedAt = Date().timeIntervalSince1970
                let existing = books[index]
                saveBooks()
                changed()
                DebugLog.log("import: duplicate detected, reusing \(existing.title)")
                return existing
            }
            let book = Fb2Bridge.parse(data: data as NSData)
            let id = UUID().uuidString
            let fileName = "\(id).fb2"
            try data.write(to: root.appendingPathComponent(fileName), options: .atomic)
            var record = BookRecord(
                id: id,
                title: book.title.isEmpty ? (url.deletingPathExtension().lastPathComponent) : book.title,
                author: book.authorLine,
                fileName: fileName,
                blockIndex: 0,
                charOffset: 0,
                percent: 0,
                blockCount: book.blocks.count,
                addedAt: Date().timeIntervalSince1970,
                lastOpenedAt: Date().timeIntervalSince1970
            )
            record.fingerprint = hash
            record.totalChars = book.blocks.reduce(0) { $0 + $1.text.count }
            books.insert(record, at: 0)
            saveBooks()
            changed()
            return record
        } catch {
            lastError = "Не удалось открыть файл: \(error.localizedDescription)"
            return nil
        }
    }

    /// Removes duplicates that slipped in earlier, keeping the most-read copy.
    func deduplicateLibrary() {
        var seen: [String: Int] = [:]
        var toRemove: [Int] = []
        for (index, record) in books.enumerated() {
            guard let hash = storedFingerprint(of: record) else { continue }
            if let firstIndex = seen[hash] {
                let keep = books[firstIndex].percent >= record.percent ? firstIndex : index
                let drop = keep == firstIndex ? index : firstIndex
                seen[hash] = keep
                if !toRemove.contains(drop) { toRemove.append(drop) }
            } else {
                seen[hash] = index
            }
        }
        guard !toRemove.isEmpty else { return }
        for index in toRemove.sorted(by: >) {
            let record = books[index]
            try? FileManager.default.removeItem(at: fileURL(for: record))
            notes.removeAll { $0.bookId == record.id }
            books.remove(at: index)
        }
        saveBooks()
        saveNotes()
        DebugLog.log("library: removed \(toRemove.count) duplicate(s)")
        changed()
    }

    func loadBook(_ record: BookRecord) -> Book? {
        do {
            let data = try Data(contentsOf: fileURL(for: record))
            return Fb2Bridge.parse(data: data as NSData)
        } catch {
            lastError = "Не удалось прочитать книгу: \(error.localizedDescription)"
            return nil
        }
    }

    func delete(_ record: BookRecord) {
        try? FileManager.default.removeItem(at: fileURL(for: record))
        books.removeAll { $0.id == record.id }
        notes.removeAll { $0.bookId == record.id }
        saveBooks()
        saveNotes()
        changed()
    }

    func updatePosition(_ record: BookRecord, blockIndex: Int, charOffset: Int, percent: Int) {
        guard let index = books.firstIndex(where: { $0.id == record.id }) else { return }
        books[index].blockIndex = blockIndex
        books[index].charOffset = charOffset
        books[index].percent = percent
        books[index].lastOpenedAt = Date().timeIntervalSince1970
        saveBooks()
        changed()
    }

    // MARK: - Notes

    func notes(for bookId: String) -> [ReaderNote] {
        notes.filter { $0.bookId == bookId }.sorted { $0.blockIndex < $1.blockIndex }
    }

    func addNote(bookId: String, blockIndex: Int, quote: String, text: String) {
        let note = ReaderNote(
            id: UUID().uuidString,
            bookId: bookId,
            blockIndex: blockIndex,
            quote: quote,
            text: text,
            createdAt: Date().timeIntervalSince1970
        )
        notes.append(note)
        saveNotes()
        changed()
    }

    func deleteNote(_ note: ReaderNote) {
        notes.removeAll { $0.id == note.id }
        saveNotes()
        changed()
    }

    // MARK: - Settings

    func updateSettings(_ transform: (inout ReaderSettings) -> Void) {
        var copy = settings
        transform(&copy)
        settings = copy
        saveSettings()
        changed()
    }

    // MARK: - Vault bridge

    /// Manifest of the whole library, ready to be encrypted into `vault.enc`.
    func vaultManifest(named name: String) -> VaultManifest {
        VaultManifest(
            version: 1,
            name: name,
            updatedAt: Int64(Date().timeIntervalSince1970 * 1000),
            books: vaultBooks(),
            notes: vaultNotes(),
            settings: vaultSettings()
        )
    }

    func vaultBooks() -> [VaultBook] {
        books.map { record in
            VaultBook(
                id: record.id,
                title: record.title,
                author: record.author,
                fileName: record.fileName,
                blockIndex: record.blockIndex,
                charOffset: record.charOffset,
                percent: record.percent,
                totalChars: record.totalChars ?? 0,
                addedAt: Self.millis(record.addedAt),
                lastOpenedAt: Self.millis(record.lastOpenedAt),
                blockCount: record.blockCount,
                hash: record.fingerprint ?? storedFingerprint(of: record) ?? ""
            )
        }
    }

    func vaultNotes() -> [VaultNote] {
        notes.map { note in
            VaultNote(
                id: note.id,
                bookId: note.bookId,
                blockIndex: note.blockIndex,
                quote: note.quote,
                text: note.text,
                createdAt: Self.millis(note.createdAt)
            )
        }
    }

    func vaultSettings() -> VaultSettings {
        VaultSettings(
            fontFamily: settings.fontFamily,
            fontSize: Int(settings.fontSize.rounded()),
            lineHeight: settings.lineHeight,
            marginH: Int(settings.marginH.rounded()),
            marginV: Int(settings.marginV.rounded()),
            theme: settings.theme,
            justified: settings.justified
        )
    }

    /// Bytes of the FB2 file of a book, used for uploading it to a vault.
    func bookData(id: String) -> Data? {
        guard let record = books.first(where: { $0.id == id }) else { return nil }
        return try? Data(contentsOf: fileURL(for: record))
    }

    /// True when the book is already here, by id or by the SHA-256 of its file.
    func hasBook(id: String, hash: String) -> Bool {
        books.contains { record in
            record.id == id || (!hash.isEmpty && (record.fingerprint ?? storedFingerprint(of: record)) == hash)
        }
    }

    /// Applies a vault snapshot: writes new books to disk, merges reading progress, replaces
    /// notes and reader settings. Books that exist only locally are kept.
    func applyVault(
        books incoming: [VaultBook],
        notes incomingNotes: [VaultNote],
        settings incomingSettings: VaultSettings,
        blobs: [String: Data]
    ) {
        syncSuspended = true
        defer { syncSuspended = false }

        for item in incoming {
            if let index = indexOf(book: item) {
                // The vault is the shared copy: adopt its id, so the same file imported on two
                // devices does not keep bouncing between two ids on every sync.
                if books[index].id != item.id, !books.contains(where: { $0.id == item.id }) {
                    books[index].id = item.id
                }
                books[index].blockIndex = item.blockIndex
                books[index].charOffset = item.charOffset
                books[index].percent = item.percent
                if item.blockCount > 0 { books[index].blockCount = item.blockCount }
                if item.totalChars > 0 { books[index].totalChars = item.totalChars }
                if item.lastOpenedAt > 0 { books[index].lastOpenedAt = Self.seconds(item.lastOpenedAt) }
                if (books[index].fingerprint ?? "").isEmpty { books[index].fingerprint = item.hash }
                continue
            }

            guard let data = blobs[item.id] else { continue }
            let fileName = item.fileName.isEmpty ? "\(item.id).fb2" : (item.fileName as NSString).lastPathComponent
            do {
                try data.write(to: root.appendingPathComponent(fileName), options: .atomic)
            } catch {
                lastError = "Не удалось сохранить книгу «\(item.title)»: \(error.localizedDescription)"
                continue
            }
            var record = BookRecord(
                id: item.id,
                title: item.title,
                author: item.author,
                fileName: fileName,
                blockIndex: item.blockIndex,
                charOffset: item.charOffset,
                percent: item.percent,
                blockCount: item.blockCount,
                addedAt: Self.seconds(item.addedAt),
                lastOpenedAt: Self.seconds(item.lastOpenedAt)
            )
            record.totalChars = item.totalChars
            record.fingerprint = item.hash.isEmpty ? Self.sha256(data) : item.hash
            books.append(record)
        }

        books.sort { $0.lastOpenedAt > $1.lastOpenedAt }
        notes = incomingNotes.map { note in
            ReaderNote(
                id: note.id,
                bookId: note.bookId,
                blockIndex: note.blockIndex,
                quote: note.quote,
                text: note.text,
                createdAt: Self.seconds(note.createdAt)
            )
        }
        settings = ReaderSettings(
            fontFamily: incomingSettings.fontFamily,
            fontSize: Double(incomingSettings.fontSize),
            lineHeight: incomingSettings.lineHeight,
            marginH: Double(incomingSettings.marginH),
            marginV: Double(incomingSettings.marginV),
            theme: incomingSettings.theme,
            justified: incomingSettings.justified
        )

        saveBooks()
        saveNotes()
        saveSettings()
        DebugLog.log("vault: applied \(incoming.count) books, \(incomingNotes.count) notes")
    }

    private func indexOf(book item: VaultBook) -> Int? {
        if let index = books.firstIndex(where: { $0.id == item.id }) { return index }
        if !item.hash.isEmpty,
           let index = books.firstIndex(where: { ($0.fingerprint ?? storedFingerprint(of: $0)) == item.hash }) {
            return index
        }
        if !item.fileName.isEmpty,
           let index = books.firstIndex(where: { $0.fileName == (item.fileName as NSString).lastPathComponent }) {
            return index
        }
        return nil
    }

    private static func millis(_ seconds: Double) -> Int64 {
        seconds > 0 ? Int64(seconds * 1000) : 0
    }

    private static func seconds(_ millis: Int64) -> Double {
        millis > 0 ? Double(millis) / 1000 : 0
    }
}

/// Small wrapper so the Swift side never touches the Kotlin class names directly.
enum Fb2Bridge {
    static func parse(data: NSData) -> Book {
        BridgeNativeKt.parseBookData(data: data as Data)
    }
}
