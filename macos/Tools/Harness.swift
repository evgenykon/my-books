import Foundation
import Shared

/// Headless check of the macOS side: parses an FB2 file, stores it in the library,
/// paginates with CoreText and persists position/notes — the same code the UI uses.
@main
struct Harness {
    static func main() async {
        let args = CommandLine.arguments
        let path = args.count > 1 ? args[1] : "samples/vojna-i-mir.fb2"
        let url = URL(fileURLWithPath: path)

        await MainActor.run { run(url) }
    }

    @MainActor
    static func run(_ url: URL) {
        let store = LibraryStore()

        print("=== Import ===")
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("FAIL: file not found \(url.path)")
            exit(1)
        }
        store.importBook(at: url)
        if let error = store.lastError {
            print("FAIL: \(error)")
            exit(1)
        }
        guard let record = store.books.first else {
            print("FAIL: library is empty after import")
            exit(1)
        }
        print("book:", record.title)
        print("author:", record.author)
        print("blocks:", record.blockCount)

        print("\n=== Parse ===")
        guard let book = store.loadBook(record) else {
            print("FAIL: cannot load book")
            exit(1)
        }
        print("title:", book.title)
        print("authors:", book.authorLine)
        print("block count:", book.blocks.count)
        print("total chars:", book.totalChars)
        print("toc:", book.toc.map { "\($0.level):\($0.title)@\($0.blockIndex)" }.joined(separator: " | "))

        print("\n=== Pagination ===")
        var settings = ReaderSettings()
        settings.fontSize = 17
        for size in [CGSize(width: 700, height: 520), CGSize(width: 420, height: 640)] {
            let pages = Paginator.paginate(book: book, settings: settings, pageSize: size)
            let chars = pages.reduce(0) { $0 + $1.slices.reduce(0) { $0 + $1.text.length } }
            print("page size \(Int(size.width))x\(Int(size.height)): pages=\(pages.count) chars=\(chars)")
            print("  used heights:", pages.prefix(4).map { Int($0.usedHeight) })
            if let first = pages.first {
                let preview = first.slices.map { $0.text.string }.joined(separator: " / ")
                print("  page 1 slices=\(first.slices.count):", preview.prefix(120))
            }
            guard let last = pages.last else { continue }
            let total = book.blocks.count
            print("  last page first block:", last.firstBlockIndex, "of", total)
        }

        print("\n=== Progress & position ===")
        let mid = book.blocks.count / 2
        let pct = Int(BridgeNativeKt.progressPercent(book: book, blockIndex: Int32(mid), charOffset: 0))
        print("percent at block \(mid):", pct)
        store.updatePosition(record, blockIndex: mid, charOffset: 3, percent: pct)
        if let saved = store.books.first(where: { $0.id == record.id }) {
            print("saved position:", saved.blockIndex, saved.charOffset, "percent:", saved.percent)
        }

        print("\n=== Notes ===")
        store.addNote(bookId: record.id, blockIndex: mid, quote: "Ну, князь, Генуя и Лукка", text: "Проверка заметки")
        let notes = store.notes(for: record.id)
        print("notes for book:", notes.count, "| quote:", notes.first?.quote ?? "-", "| text:", notes.first?.text ?? "-")
        if let note = notes.first { store.deleteNote(note) }
        print("after delete:", store.notes(for: record.id).count)

        print("\n=== Settings ===")
        store.updateSettings { $0.fontSize = 21; $0.theme = 1 }
        print("settings:", store.settings.fontSize, "theme:", store.settings.theme)
        store.updateSettings { $0 = ReaderSettings() }

        print("\nOK")
    }
}
