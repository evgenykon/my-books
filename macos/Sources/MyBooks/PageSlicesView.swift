import SwiftUI
import AppKit

/// Renders one page as a stack of lines, each with an explicit frame height, so the
/// line-height setting is applied by us and does not depend on SwiftUI text metrics.
struct PageSlicesView: View {
    let lines: [PageLine]
    let settings: ReaderSettings
    var onNote: (PageLine) -> Void = { _ in }
    var onCopy: (PageLine) -> Void = { _ in }

    private var palette: ReaderPalette { ReaderPalette.of(settings) }

    private func color(for kind: String) -> Color {
        switch kind {
        case "SUBTITLE", "EPIGRAPH", "EPIGRAPH_AUTHOR", "ANNOTATION":
            return Color(nsColor: NSColor(hex: palette.secondary))
        default:
            return Color(nsColor: NSColor(hex: palette.text))
        }
    }

    private func alignment(for kind: String) -> Alignment {
        switch kind {
        case "HEADING1", "HEADING2": return .center
        case "EPIGRAPH_AUTHOR": return .trailing
        default: return .leading
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(lines) { line in
                let m = Paginator.metrics(kind: line.kind, settings: settings)
                Text(line.text)
                    .font(Font(m.font))
                    .foregroundStyle(color(for: line.kind))
                    .lineLimit(1)
                    .frame(
                        maxWidth: .infinity,
                        minHeight: m.lineHeight,
                        maxHeight: m.lineHeight,
                        alignment: alignment(for: line.kind)
                    )
                    .padding(.leading, m.leadingIndent)
                    .padding(.top, line.isFirstOfBlock ? m.spaceBefore : 0)
                    .padding(.bottom, line.isLastOfBlock ? m.spaceAfter : 0)
                    .textSelection(.enabled)
                    .contextMenu {
                        Button("Копировать строку") { onCopy(line) }
                        Button("Добавить заметку") { onNote(line) }
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
