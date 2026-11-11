import AppKit
import CoreText
import Foundation
import Shared

/// One rendered line of text on a page.
struct PageLine: Identifiable {
    let id = UUID()
    let blockIndex: Int
    let kind: String
    let text: String
    let isFirstOfBlock: Bool
    let isLastOfBlock: Bool
}

struct ReaderPage: Identifiable {
    let id = UUID()
    var lines: [PageLine]
    var usedHeight: CGFloat = 0
    var firstBlockIndex: Int { lines.first?.blockIndex ?? 0 }
}

enum Paginator {

    // MARK: - Fonts

    static let familyNames = ["PT Serif", "Charter", "Iowan Old Style", "Georgia", "Helvetica Neue", "Menlo"]

    static func font(family: Int, size: CGFloat, weight: NSFont.Weight) -> NSFont {
        let index = min(max(family, 0), familyNames.count - 1)
        let name = familyNames[index]
        if let font = NSFont(name: name, size: size) {
            if weight != .regular {
                return NSFontManager.shared.convert(font, toHaveTrait: .boldFontMask)
            }
            return font
        }
        if index == familyNames.count - 1 {
            return NSFont.monospacedSystemFont(ofSize: size, weight: weight)
        }
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        if let descriptor = base.fontDescriptor.withDesign(.serif), let font = NSFont(descriptor: descriptor, size: size) {
            return font
        }
        return base
    }

    // MARK: - Metrics

    struct BlockMetrics {
        var font: NSFont
        var fontSize: CGFloat
        /// Exact height of one line box — what the line-height slider controls.
        var lineHeight: CGFloat
        /// Space above the block (chapter headings get a lot of air).
        var spaceBefore: CGFloat
        /// Space below the block.
        var spaceAfter: CGFloat
        /// Left inset for quoted blocks such as epigraphs.
        var leadingIndent: CGFloat
    }

    static func metrics(kind: String, settings: ReaderSettings) -> BlockMetrics {
        var size = CGFloat(settings.fontSize)
        var weight: NSFont.Weight = .regular
        var lineFactor: CGFloat = 1
        var spaceBefore: CGFloat = 0
        var spaceAfter = size * 0.85
        var leadingIndent: CGFloat = 0

        switch kind {
        case "HEADING1":
            size *= 1.5
            weight = .bold
            lineFactor = 1.2
            spaceBefore = size * 1.1
            spaceAfter = size * 0.6
        case "HEADING2":
            size *= 1.28
            weight = .semibold
            lineFactor = 1.15
            spaceBefore = size * 0.9
            spaceAfter = size * 0.45
        case "HEADING3":
            size *= 1.12
            weight = .semibold
            spaceBefore = size * 0.65
            spaceAfter = size * 0.35
        case "SUBTITLE":
            weight = .medium
            spaceBefore = size * 0.6
            spaceAfter = size * 0.5
        case "EPIGRAPH":
            size *= 0.92
            spaceBefore = size * 0.9
            spaceAfter = size * 0.2
            leadingIndent = size * 3.2
        case "EPIGRAPH_AUTHOR":
            size *= 0.88
            spaceAfter = size * 1.1
            leadingIndent = size * 3.2
        case "POEM":
            size *= 0.95
            spaceAfter = size * 0.2
            leadingIndent = size * 1.4
        default:
            break
        }

        let font = Self.font(family: settings.fontFamily, size: size, weight: weight)
        let natural = ceil(font.ascender - font.descender + font.leading)
        let lineHeight = max(natural, (size * CGFloat(settings.lineHeight) * lineFactor).rounded())

        return BlockMetrics(
            font: font,
            fontSize: size,
            lineHeight: lineHeight,
            spaceBefore: spaceBefore,
            spaceAfter: spaceAfter,
            leadingIndent: leadingIndent
        )
    }

    /// Paragraph indent as characters (SwiftUI drops `firstLineHeadIndent`).
    static let indentPrefix = "\u{2003}\u{2002}"

    private static func attributedText(kind: String, text: String, settings: ReaderSettings) -> NSAttributedString {
        let body = kind == "PARAGRAPH" ? indentPrefix + text : text
        return NSAttributedString(string: body, attributes: [.font: metrics(kind: kind, settings: settings).font])
    }

    // MARK: - Pagination

    /// Splits the book into pages of whole lines. Because the page view renders each
    /// line with an explicit frame height, the layout matches the measurement exactly.
    static func paginate(book: Book, settings: ReaderSettings, pageSize: CGSize) -> [ReaderPage] {
        let width = pageSize.width
        let height = pageSize.height
        guard width > 40, height > 40 else { return [] }

        var pages: [ReaderPage] = []
        var lines: [PageLine] = []
        var used: CGFloat = 0

        func flush() {
            if !lines.isEmpty {
                pages.append(ReaderPage(lines: lines, usedHeight: used))
                lines = []
            }
            used = 0
        }

        for block in book.blocks {
            let kind = block.kindName
            let text = block.text

            if text.isEmpty {
                let extra = CGFloat(settings.fontSize) * 0.6
                if used + extra > height { flush() } else { used += extra }
                continue
            }

            let m = metrics(kind: kind, settings: settings)
            if used + m.spaceBefore + m.lineHeight > height { flush() }
            used += m.spaceBefore

            let attr = attributedText(kind: kind, text: text, settings: settings)
            let framesetter = CTFramesetterCreateWithAttributedString(attr)
            let path = CGPath(
                rect: CGRect(x: 0, y: 0, width: max(width - m.leadingIndent, 40), height: 100_000),
                transform: nil
            )
            let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), path, nil)
            let ctLines = (CTFrameGetLines(frame) as? [CTLine]) ?? []
            let source = attr.string as NSString

            var blockLines: [String] = []
            for ctLine in ctLines {
                let range = CTLineGetStringRange(ctLine)
                guard range.length > 0 else { continue }
                let piece = source.substring(with: NSRange(location: range.location, length: range.length))
                let trimmed = piece.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { blockLines.append(trimmed) }
            }
            if blockLines.isEmpty { blockLines = [text] }

            for (index, lineText) in blockLines.enumerated() {
                if used + m.lineHeight > height { flush() }
                lines.append(
                    PageLine(
                        blockIndex: Int(block.index),
                        kind: kind,
                        text: lineText,
                        isFirstOfBlock: index == 0,
                        isLastOfBlock: index == blockLines.count - 1
                    )
                )
                used += m.lineHeight
                if index == blockLines.count - 1 { used += m.spaceAfter }
            }
        }

        flush()
        if pages.isEmpty { pages.append(ReaderPage(lines: [])) }
        return pages
    }

    /// Index of the page holding the given block.
    static func pageIndex(pages: [ReaderPage], forBlock blockIndex: Int) -> Int {
        var result = 0
        for (index, page) in pages.enumerated() {
            if page.firstBlockIndex <= blockIndex { result = index } else { break }
        }
        return min(max(result, 0), max(pages.count - 1, 0))
    }
}
