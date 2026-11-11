import SwiftUI
import AppKit

/// Converts a CoreText-produced attributed string into a SwiftUI attributable text.
func swiftUIAttributed(_ ns: NSAttributedString) -> AttributedString {
    (try? AttributedString(ns, including: \.appKit)) ?? AttributedString(ns.string)
}

/// Renders the slices of one page: the text the paginator decided fits here.
struct PageSlicesView: View {
    let slices: [PageSlice]
    let settings: ReaderSettings
    var onNote: (PageSlice) -> Void = { _ in }
    var onCopy: (PageSlice) -> Void = { _ in }

    /// SwiftUI draws slightly tighter than CoreText measures; this closes the gap so a
    /// page filled by the paginator is also visually filled.
    static let extraSpacing: CGFloat = {
        if let raw = ProcessInfo.processInfo.environment["MYBOOKS_LINE_K"], let value = Double(raw) {
            return CGFloat(value)
        }
        return 0.0
    }()

    private var palette: ReaderPalette { ReaderPalette.of(settings) }

    private func color(for kind: String) -> Color {
        switch kind {
        case "SUBTITLE", "EPIGRAPH", "EPIGRAPH_AUTHOR", "ANNOTATION":
            return Color(nsColor: NSColor(hex: palette.secondary))
        default:
            return Color(nsColor: NSColor(hex: palette.text))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(slices) { slice in
                let m = Paginator.metrics(kind: slice.kind, settings: settings)
                Text(swiftUIAttributed(slice.text))
                    .foregroundStyle(color(for: slice.kind))
                    .lineSpacing(m.swiftUILineSpacing + Self.extraSpacing * CGFloat(settings.fontSize))
                    .padding(.bottom, m.paragraphSpacing)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contextMenu {
                        Button("Копировать абзац") { onCopy(slice) }
                        Button("Добавить заметку") { onNote(slice) }
                    }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, settings.marginH)
        .padding(.vertical, settings.marginV)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// Reader header: book, author, page counter, progress and the panel buttons.
struct ReaderHeaderView: View {
    let title: String
    let author: String
    let page: Int
    let pageCount: Int
    let percent: Int
    let palette: ReaderPalette
    var onBack: () -> Void = {}
    var onToc: () -> Void = {}
    var onNotes: () -> Void = {}
    var onSettings: () -> Void = {}

    var body: some View {
        HStack(spacing: 14) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
            }
            .buttonStyle(.borderless)
            .help("Библиотека")

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text(author)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            if pageCount > 0 {
                HStack(spacing: 10) {
                    Text("стр. \(page) из \(pageCount)")
                    Text("\(percent)%").fontWeight(.medium)
                }
                .font(.system(size: 11))
                .monospacedDigit()
                .foregroundStyle(.secondary)
            }

            Divider().frame(height: 16)

            Button(action: onToc) { Image(systemName: "list.bullet") }
                .buttonStyle(.borderless)
                .help("Содержание")
            Button(action: onNotes) { Image(systemName: "note.text") }
                .buttonStyle(.borderless)
                .help("Заметки")
            Button(action: onSettings) { Image(systemName: "textformat.size") }
                .buttonStyle(.borderless)
                .help("Настройки")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .foregroundStyle(Color(nsColor: NSColor(hex: palette.text)))
    }
}
