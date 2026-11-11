import SwiftUI
import UniformTypeIdentifiers
import AppKit

struct LibraryView: View {
    @ObservedObject var store: LibraryStore
    var onOpen: (BookRecord) -> Void

    @State private var importing = false
    @State private var pendingDelete: BookRecord?

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if store.books.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(store.books) { book in
                        BookRow(
                            record: book,
                            noteCount: store.notes(for: book.id).count,
                            onOpen: { onOpen(book) },
                            onDelete: {
                                DebugLog.log("delete: requested for \(book.title)")
                                pendingDelete = book
                            }
                        )
                            .contextMenu {
                                Button("Открыть") { onOpen(book) }
                                Button("Удалить", role: .destructive) { pendingDelete = book }
                            }
                    }
                }
                .listStyle(.inset)
            }
        }
        .background(Color(nsColor: NSColor(hex: ReaderPalette.of(store.settings).background)))
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: [UTType(filenameExtension: "fb2") ?? .xml],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                store.importBook(at: url)
            case .failure(let error):
                store.lastError = "Не удалось выбрать файл: \(error.localizedDescription)"
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .myBooksImport)) { _ in
            importing = true
        }
        .alert(
            "Удалить книгу?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            Button("Отмена", role: .cancel) { pendingDelete = nil }
            Button("Удалить", role: .destructive) {
                if let book = pendingDelete {
                    DebugLog.log("delete: confirmed \(book.title)")
                    store.delete(book)
                    DebugLog.log("delete: done, books=\(store.books.count) error=\(store.lastError ?? "-")")
                }
                pendingDelete = nil
            }
        } message: {
            Text("Книга и все заметки к ней будут удалены.")
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Моя библиотека").font(.title3.weight(.semibold))
                Text(bookCountLabel).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                importing = true
            } label: {
                Label("Добавить книгу", systemImage: "plus")
            }
            .keyboardShortcut("o")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    private var bookCountLabel: String {
        let count = store.books.count
        if count == 0 { return "Книг пока нет" }
        return "Книг: \(count)"
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(.secondary)
            Text("Библиотека пуста")
                .font(.headline)
            Text("Добавьте файл FB2, чтобы начать читать.")
                .font(.callout)
                .foregroundStyle(.secondary)
            Button("Добавить книгу") { importing = true }
                .buttonStyle(.borderedProminent)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct BookRow: View {
    let record: BookRecord
    let noteCount: Int
    var onOpen: () -> Void = {}
    var onDelete: () -> Void = {}

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Button(action: onOpen) {
                HStack(alignment: .center, spacing: 14) {
                    cover
                    info
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer(minLength: 0)

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(.secondary)
                    .frame(width: 24, height: 24)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .help("Удалить книгу")
        }
        .padding(.vertical, 8)
    }

    private var cover: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color(nsColor: NSColor(hex: "#E8E2D6")))
            Text(initials)
                .font(.system(size: 15, weight: .semibold, design: .serif))
                .foregroundStyle(Color.black.opacity(0.55))
        }
        .frame(width: 38, height: 52)
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(record.title).font(.system(size: 14, weight: .medium)).lineLimit(1)
            Text(record.author).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
            HStack(spacing: 8) {
                ProgressView(value: Double(record.percent), total: 100)
                    .frame(width: 120)
                Text("\(record.percent)%").font(.system(size: 11)).foregroundStyle(.secondary).monospacedDigit()
                if noteCount > 0 {
                    Label("\(noteCount)", systemImage: "note.text")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 2)
        }
    }

    private var initials: String {
        let letters = record.title.split(separator: " ").prefix(2).compactMap { $0.first }
        return String(letters).uppercased()
    }
}
