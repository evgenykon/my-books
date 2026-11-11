import SwiftUI
import AppKit
import Shared

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

struct ReaderView: View {
    let record: BookRecord
    @ObservedObject var store: LibraryStore
    var onClose: () -> Void

    enum PanelKind { case toc, notes, settings }

    struct NoteDraft: Identifiable {
        let id = UUID()
        let blockIndex: Int
        let quote: String
    }

    @State private var book: Book?
    @State private var pages: [ReaderPage] = []
    @State private var pageIndex = 0
    @State private var percent = 0
    @State private var panel: PanelKind?
    @State private var noteDraft: NoteDraft?
    @State private var isPaginating = false
    @State private var restored = false
    @State private var loadFailed = false

    @FocusState private var focused: Bool

    private var settings: ReaderSettings { store.settings }
    private var palette: ReaderPalette { ReaderPalette.of(settings) }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            HStack(spacing: 0) {
                readerArea
                if let panel {
                    Divider()
                    panelView(panel)
                        .frame(width: 300)
                        .transition(.move(edge: .trailing))
                }
            }
        }
        .background(Color(nsColor: NSColor(hex: palette.background)))
        .focusable()
        .focused($focused)
        .onAppear {
            focused = true
            loadIfNeeded()
        }
        .onKeyPress(.rightArrow) { nextPage(); return .handled }
        .onKeyPress(.space) { nextPage(); return .handled }
        .onKeyPress(.pageDown) { nextPage(); return .handled }
        .onKeyPress(.leftArrow) { previousPage(); return .handled }
        .onKeyPress(.pageUp) { previousPage(); return .handled }
        .sheet(item: $noteDraft) { draft in
            NoteEditorView(quote: draft.quote) { text in
                store.addNote(bookId: record.id, blockIndex: draft.blockIndex, quote: draft.quote, text: text)
                panel = .notes
            }
        }
        .onChange(of: pageIndex) { _, _ in
            updatePercent()
            savePosition()
        }
    }

    // MARK: - Loading

    private func loadIfNeeded() {
        if book == nil {
            book = store.loadBook(record)
            loadFailed = book == nil
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            Button {
                onClose()
            } label: {
                Image(systemName: "chevron.left")
            }
            .buttonStyle(.borderless)
            .help("Библиотека")

            VStack(alignment: .leading, spacing: 1) {
                Text(book?.title ?? record.title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text(book?.authorLine ?? record.author)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            if !pages.isEmpty {
                HStack(spacing: 6) {
                    Button { previousPage() } label: { Image(systemName: "chevron.left") }
                        .buttonStyle(.borderless)
                        .disabled(pageIndex == 0)
                        .help("Предыдущая страница")
                    Text("стр. \(pageIndex + 1) из \(pages.count)")
                    Button { nextPage() } label: { Image(systemName: "chevron.right") }
                        .buttonStyle(.borderless)
                        .disabled(pageIndex + 1 >= pages.count)
                        .help("Следующая страница")
                    Text("\(percent)%")
                        .fontWeight(.medium)
                }
                .font(.system(size: 11))
                .monospacedDigit()
                .foregroundStyle(.secondary)
            }

            Divider().frame(height: 16)

            Button {
                togglePanel(.toc)
            } label: {
                Image(systemName: "list.bullet")
            }
            .buttonStyle(.borderless)
            .help("Содержание")

            Button {
                togglePanel(.notes)
            } label: {
                Image(systemName: "note.text")
            }
            .buttonStyle(.borderless)
            .help("Заметки")

            Button {
                togglePanel(.settings)
            } label: {
                Image(systemName: "textformat.size")
            }
            .buttonStyle(.borderless)
            .help("Настройки")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
    }

    private func togglePanel(_ kind: PanelKind) {
        panel = panel == kind ? nil : kind
    }

    // MARK: - Reader area

    private var readerArea: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width - settings.marginH * 2, 80)
            let height = max(geometry.size.height - settings.marginV * 2, 80)

            ZStack {
                if loadFailed {
                    VStack(spacing: 8) {
                        Text("Не удалось открыть книгу").font(.headline)
                        Text("Файл повреждён или недоступен.").font(.callout).foregroundStyle(.secondary)
                    }
                } else if pages.isEmpty {
                    ProgressView().controlSize(.small)
                } else {
                    pageContent
                }
            }
            .task(id: layoutKey(width: width, height: height)) {
                await repaginate(size: CGSize(width: width, height: height))
            }
        }
    }

    private func layoutKey(width: CGFloat, height: CGFloat) -> String {
        "\(Int(width))x\(Int(height))-\(settings.fontFamily)-\(settings.fontSize)-\(settings.lineHeight)-\(settings.justified)"
    }

    @MainActor
    private func repaginate(size: CGSize) async {
        guard let book else { return }
        isPaginating = true
        let current = settings
        let anchorBlock = pages[safe: pageIndex]?.firstBlockIndex ?? record.blockIndex
        let result = await Task.detached(priority: .userInitiated) {
            Paginator.paginate(book: book, settings: current, pageSize: size)
        }.value
        pages = result
        isPaginating = false
        DebugLog.log("pagination: size=\(Int(size.width))x\(Int(size.height)) pages=\(result.count) font=\(current.fontSize)")
        if result.isEmpty { return }
        if restored {
            pageIndex = Paginator.pageIndex(pages: result, forBlock: anchorBlock)
        } else {
            pageIndex = Paginator.pageIndex(pages: result, forBlock: record.blockIndex)
            restored = true
        }
        updatePercent()
    }

    private var pageContent: some View {
        let page = pages[safe: pageIndex]
        return PageSlicesView(
            lines: page?.lines ?? [],
            settings: settings,
            onNote: { line in
                noteDraft = NoteDraft(blockIndex: line.blockIndex, quote: line.text)
            },
            onCopy: { line in
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(line.text, forType: .string)
            }
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            PagingEventBridge(
                onNext: {
                    DebugLog.log("input: next")
                    nextPage()
                },
                onPrevious: {
                    DebugLog.log("input: previous")
                    previousPage()
                }
            )
        )
    }

    // MARK: - Navigation

    private func nextPage() {
        focused = true
        guard pageIndex + 1 < pages.count else { return }
        withAnimation(.easeInOut(duration: 0.14)) { pageIndex += 1 }
    }

    private func previousPage() {
        focused = true
        guard pageIndex > 0 else { return }
        withAnimation(.easeInOut(duration: 0.14)) { pageIndex -= 1 }
    }

    private func jump(toBlock blockIndex: Int) {
        guard !pages.isEmpty else { return }
        pageIndex = Paginator.pageIndex(pages: pages, forBlock: blockIndex)
    }

    private func updatePercent() {
        guard let book, let page = pages[safe: pageIndex] else { return }
        percent = Int(BridgeNativeKt.progressPercent(book: book, blockIndex: Int32(page.firstBlockIndex), charOffset: 0))
    }

    private func savePosition() {
        guard let page = pages[safe: pageIndex] else { return }
        store.updatePosition(record, blockIndex: page.firstBlockIndex, charOffset: 0, percent: percent)
    }

    // MARK: - Panels

    @ViewBuilder
    private func panelView(_ kind: PanelKind) -> some View {
        switch kind {
        case .toc:
            TocPanel(
                entries: book?.toc ?? [],
                palette: palette,
                currentBlock: pages[safe: pageIndex]?.firstBlockIndex ?? 0
            ) { blockIndex in
                jump(toBlock: blockIndex)
            }
        case .notes:
            NotesPanel(
                notes: store.notes(for: record.id),
                palette: palette,
                onSelect: { note in jump(toBlock: note.blockIndex) },
                onDelete: { store.deleteNote($0) }
            )
        case .settings:
            SettingsPanel(settings: settings) { updated in
                store.updateSettings { $0 = updated }
            }
        }
    }
}

struct NoteEditorView: View {
    let quote: String
    var onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var includeQuote = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Новая заметка").font(.headline)
            ScrollView {
                Text("«\(quote)»")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 90)
            TextEditor(text: $text)
                .font(.system(size: 13))
                .frame(minHeight: 110)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.25)))
            Toggle("Добавить цитату в текст заметки", isOn: $includeQuote)
                .font(.caption)
            HStack {
                Spacer()
                Button("Отмена") { dismiss() }
                Button("Сохранить") {
                    let body = includeQuote ? "\(text)\n\n> \(quote)" : text
                    onSave(body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? quote : body)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(18)
        .frame(width: 420)
    }
}
