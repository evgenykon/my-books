import AppKit
import CoreText
import Foundation
import Shared

/// One piece of a block that fits on a page.
struct PageSlice: Identifiable {
    let id = UUID()
    let blockIndex: Int
    let kind: String
    let text: NSAttributedString
    let continuation: Bool
}

struct ReaderPage: Identifiable {
    let id = UUID()
    var slices: [PageSlice]
    var usedHeight: CGFloat = 0
    var firstBlockIndex: Int { slices.first?.blockIndex ?? 0 }
}

enum Paginator {

    // MARK: - Fonts & styles

    static let familyNames = ["PT Serif", "Charter", "Iowan Old Style", "Georgia", "Helvetica Neue", "Menlo"]

    static func font(family: Int, size: CGFloat, weight: NSFont.Weight) -> NSFont {
        let index = min(max(family, 0), familyNames.count - 1)
        let name = familyNames[index]
        if let font = NSFont(name: name, size: size) {
            // apply weight by re-instantiating the family with a bold/medium face when possible
            if weight != .regular, let bolded = NSFontManager.shared.convert(font, toHaveTrait: .boldFontMask) as NSFont? {
                return bolded
            }
            return font
        }
        if index == familyNames.count - 1 {
            return NSFont.monospacedSystemFont(ofSize: size, weight: weight)
        }
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        if let descriptor = base.fontDescriptor.withDesign(index == familyNames.count - 2 ? .default : .serif),
           let font = NSFont(descriptor: descriptor, size: size) {
            return font
        }
        return base
    }

    /// Line height used for spacing maths and safety margins.
    static func lineHeight(_ settings: ReaderSettings) -> CGFloat {
        metrics(kind: "PARAGRAPH", settings: settings).targetLineHeight
    }

    /// Calibration: SwiftUI renders text a little tighter than CoreText measures, so the
    /// line height used for pagination must match what is actually drawn.
    static let heightK: CGFloat = {
        if let raw = ProcessInfo.processInfo.environment["MYBOOKS_HEIGHT_K"], let value = Double(raw) {
            return CGFloat(value)
        }
        return 0.68
    }()

    struct BlockMetrics {
        var font: NSFont
        var fontSize: CGFloat
        var targetLineHeight: CGFloat
        /// Space above a block (chapter headings get a lot of air).
        var spaceBefore: CGFloat
        /// Space below a block.
        var paragraphSpacing: CGFloat
        /// Left inset for quoted blocks such as epigraphs.
        var leadingIndent: CGFloat
        /// What SwiftUI needs in `.lineSpacing` to reach `targetLineHeight`.
        var swiftUILineSpacing: CGFloat
    }

    static func metrics(kind: String, settings: ReaderSettings) -> BlockMetrics {
        var size = CGFloat(settings.fontSize)
        var weight: NSFont.Weight = .regular
        var spacing = size * 0.85
        var spaceBefore: CGFloat = 0
        var leadingIndent: CGFloat = 0
        var lineFactor: CGFloat = 1

        switch kind {
        case "HEADING1":
            size *= 1.5
            weight = .bold
            lineFactor = 1.2
            spacing = size * 1.1
            spaceBefore = size * 2.0
        case "HEADING2":
            size *= 1.28
            weight = .semibold
            lineFactor = 1.15
            spacing = size * 0.8
            spaceBefore = size * 1.5
        case "HEADING3":
            size *= 1.12
            weight = .semibold
            spacing = size * 0.6
            spaceBefore = size * 1.0
        case "SUBTITLE":
            weight = .medium
            spacing = size * 0.5
            spaceBefore = size * 0.6
        case "EPIGRAPH":
            size *= 0.92
            spacing = size * 0.2
            spaceBefore = size * 0.9
            leadingIndent = size * 3.2
        case "EPIGRAPH_AUTHOR":
            size *= 0.88
            spacing = size * 1.1
            leadingIndent = size * 3.2
        case "POEM":
            size *= 0.95
            spacing = size * 0.2
            leadingIndent = size * 1.4
        default:
            break
        }

        let font = Self.font(family: settings.fontFamily, size: size, weight: weight)
        let defaultLineHeight = ceil(font.ascender - font.descender + font.leading)
        let target = (size * CGFloat(settings.lineHeight) * lineFactor * Self.heightK).rounded()
        return BlockMetrics(
            font: font,
            fontSize: size,
            targetLineHeight: target,
            spaceBefore: spaceBefore,
            paragraphSpacing: spacing,
            leadingIndent: leadingIndent,
            swiftUILineSpacing: max(0, target - defaultLineHeight)
        )
    }

    static func attributes(kind: String, settings: ReaderSettings) -> [NSAttributedString.Key: Any] {
        let m = metrics(kind: kind, settings: settings)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = settings.justified ? .justified : .natural
        paragraph.lineBreakMode = .byWordWrapping

        // SwiftUI ignores NSParagraphStyle.lineHeightMultiple / paragraphSpacing, so the
        // page view reproduces them with .lineSpacing and .padding — both derived here.
        let defaultLineHeight = ceil(m.font.ascender - m.font.descender + m.font.leading)
        paragraph.minimumLineHeight = m.targetLineHeight
        paragraph.maximumLineHeight = m.targetLineHeight
        _ = defaultLineHeight

        switch kind {
        case "HEADING1", "HEADING2":
            paragraph.alignment = .center
        case "EPIGRAPH":
            paragraph.headIndent = m.fontSize * 1.2
            paragraph.firstLineHeadIndent = m.fontSize * 1.2
        case "EPIGRAPH_AUTHOR":
            paragraph.alignment = .right
        case "POEM":
            paragraph.firstLineHeadIndent = m.fontSize * 1.4
            paragraph.headIndent = m.fontSize * 1.4
        default:
            break
        }

        // Only the font and the line spacing travel with the string: SwiftUI applies
        // alignment/indents itself, and NSParagraphStyle from AttributedString is unreliable.
        paragraph.lineSpacing = m.swiftUILineSpacing
        return [
            .font: m.font,
            .paragraphStyle: paragraph,
        ]
    }

    /// Paragraph indent rendered as characters: SwiftUI ignores `firstLineHeadIndent`.
    static let indentPrefix = "\u{2003}\u{2002}"

    static func attributed(kind: String, text: String, settings: ReaderSettings) -> NSAttributedString {
        let body = kind == "PARAGRAPH" ? indentPrefix + text : text
        return NSAttributedString(string: body, attributes: attributes(kind: kind, settings: settings))
    }

    // MARK: - Pagination

    /// Measures how many characters of `attr` fit into `available` height at `width`.
    private static func fill(
        attr: NSAttributedString,
        start: Int,
        width: CGFloat,
        available: CGFloat
    ) -> (chars: Int, height: CGFloat) {
        guard available > 1, width > 1 else { return (0, 0) }
        let framesetter = CTFramesetterCreateWithAttributedString(attr)
        let path = CGPath(rect: CGRect(x: 0, y: 0, width: width, height: available), transform: nil)
        let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: start, length: 0), path, nil)
        guard let lines = CTFrameGetLines(frame) as? [CTLine], !lines.isEmpty else { return (0, 0) }

        var origins = [CGPoint](repeating: .zero, count: lines.count)
        CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)

        let lastIndex = lines.count - 1
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        var leading: CGFloat = 0
        _ = CTLineGetTypographicBounds(lines[lastIndex], &ascent, &descent, &leading)

        let lastRange = CTLineGetStringRange(lines[lastIndex])
        let end = lastRange.location + lastRange.length
        let chars = end - start
        guard chars > 0 else { return (0, 0) }

        let consumed = available - (origins[lastIndex].y - descent)
        return (chars, min(max(consumed, 1), available))
    }

    static func paginate(book: Book, settings: ReaderSettings, pageSize: CGSize) -> [ReaderPage] {
        let width = pageSize.width
        let height = pageSize.height
        guard width > 40, height > 40 else { return [] }

        let safety = lineHeight(settings) * 0.3
        var pages: [ReaderPage] = []
        var slices: [PageSlice] = []
        var used: CGFloat = 0

        func flush() {
            if !slices.isEmpty {
                pages.append(ReaderPage(slices: slices, usedHeight: used))
                slices = []
            }
            used = 0
        }

        for block in book.blocks {
            let kind = block.kindName
            let text = block.text

            if text.isEmpty {
                let extra = CGFloat(settings.fontSize) * 0.8
                if used + extra > height - safety { flush() } else { used += extra }
                continue
            }

            let blockMetrics = metrics(kind: kind, settings: settings)
            if blockMetrics.spaceBefore > 0 {
                if used + blockMetrics.spaceBefore > height - safety {
                    flush()
                }
                used += blockMetrics.spaceBefore
            }
            let attr = attributed(kind: kind, text: text, settings: settings)
            let total = attr.length
            var start = 0
            var first = true

            while start < total {
                if height - used - safety < lineHeight(settings) * 0.6 {
                    flush()
                    continue
                }
                let available = height - used - safety
                let (chars, consumed) = fill(attr: attr, start: start, width: width, available: available)
                if chars <= 0 {
                    if slices.isEmpty {
                        // Page is empty but nothing fits: force one character forward.
                        let length = min(1, total - start)
                        let part = attr.attributedSubstring(from: NSRange(location: start, length: length))
                        slices.append(PageSlice(blockIndex: Int(block.index), kind: kind, text: part, continuation: !first))
                        start += length
                        flush()
                        continue
                    }
                    flush()
                    continue
                }
                let part = attr.attributedSubstring(from: NSRange(location: start, length: chars))
                slices.append(PageSlice(blockIndex: Int(block.index), kind: kind, text: part, continuation: !first))
                used += consumed + metrics(kind: kind, settings: settings).paragraphSpacing
                start += chars
                first = false
                if start < total { flush() }
            }
        }
        flush()
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
