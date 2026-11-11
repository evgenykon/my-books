import Foundation
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
    var lineHeight: Double = 1.45
    var marginH: Double = 56
    var marginV: Double = 32
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

    private let root: URL
    private let booksFile: URL
    private let notesFile: URL
    private let settingsFile: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        root = base.appendingPathComponent("MyBooks", isDirectory: true)
        booksFile = root.appendingPathComponent("books.json")
        notesFile = root.appendingPathComponent("notes.json")
        settingsFile = root.appendingPathComponent("reader-settings.json")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        load()
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

    // MARK: - Books

    func fileURL(for record: BookRecord) -> URL {
        root.appendingPathComponent(record.fileName)
    }

    func importBook(at url: URL) {
        do {
            let data = try Data(contentsOf: url)
            let book = Fb2Bridge.parse(data: data as NSData)
            let id = UUID().uuidString
            let fileName = "\(id).fb2"
            try data.write(to: root.appendingPathComponent(fileName), options: .atomic)
            let record = BookRecord(
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
            books.insert(record, at: 0)
            saveBooks()
        } catch {
            lastError = "Не удалось открыть файл: \(error.localizedDescription)"
        }
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
    }

    func updatePosition(_ record: BookRecord, blockIndex: Int, charOffset: Int, percent: Int) {
        guard let index = books.firstIndex(where: { $0.id == record.id }) else { return }
        books[index].blockIndex = blockIndex
        books[index].charOffset = charOffset
        books[index].percent = percent
        books[index].lastOpenedAt = Date().timeIntervalSince1970
        saveBooks()
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
    }

    func deleteNote(_ note: ReaderNote) {
        notes.removeAll { $0.id == note.id }
        saveNotes()
    }

    // MARK: - Settings

    func updateSettings(_ transform: (inout ReaderSettings) -> Void) {
        var copy = settings
        transform(&copy)
        settings = copy
        saveSettings()
    }
}

/// Small wrapper so the Swift side never touches the Kotlin class names directly.
enum Fb2Bridge {
    static func parse(data: NSData) -> Book {
        BridgeNativeKt.parseBookData(data: data as Data)
    }
}
