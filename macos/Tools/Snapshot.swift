import Foundation
import AppKit
import SwiftUI
import Shared

/// Renders reader pages to PNG with SwiftUI's ImageRenderer, so the layout can be
/// checked without a screen capture permission.
@main
struct Snapshot {
    static func main() async {
        let args = CommandLine.arguments
        let bookPath = args.count > 1 ? args[1] : "../samples/vojna-i-mir.fb2"
        let outDir = args.count > 2 ? args[2] : ".build/snapshots"
        await MainActor.run { render(bookPath: bookPath, outDir: outDir) }
    }

    @MainActor
    static func render(bookPath: String, outDir: String) {
        let url = URL(fileURLWithPath: bookPath)
        guard let data = try? Data(contentsOf: url) else {
            print("FAIL: cannot read \(bookPath)")
            exit(1)
        }
        let book = Fb2Bridge.parse(data: data as NSData)
        print("book:", book.title, "blocks:", book.blocks.count)

        try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

        let pageWidth: CGFloat = 900
        let pageHeight: CGFloat = 570

        for theme in 0...2 {
            var settings = ReaderSettings()
            settings.theme = theme
            settings.fontSize = 17
            if let raw = ProcessInfo.processInfo.environment["MYBOOKS_LINEHEIGHT"], let value = Double(raw) { settings.lineHeight = value }
            settings.marginH = 64
            settings.marginV = 40

            let pages = Paginator.paginate(
                book: book,
                settings: settings,
                pageSize: CGSize(width: pageWidth - settings.marginH * 2, height: pageHeight - settings.marginV * 2)
            )
            guard let page = pages.first else {
                print("FAIL: no pages for theme \(theme)")
                continue
            }
            let palette = ReaderPalette.of(settings)
            let percent = Int(BridgeNativeKt.progressPercent(book: book, blockIndex: Int32(page.firstBlockIndex), charOffset: 0))

            let view = VStack(spacing: 0) {
                ReaderHeaderView(
                    title: book.title,
                    author: book.authorLine,
                    page: 1,
                    pageCount: pages.count,
                    percent: percent,
                    palette: palette
                )
                Divider()
                PageSlicesView(lines: page.lines, settings: settings)
            }
            .frame(width: pageWidth, height: pageHeight)
            .background(Color(nsColor: NSColor(hex: palette.background)))

            let renderer = ImageRenderer(content: view)
            renderer.scale = 2
            guard let image = renderer.nsImage,
                  let tiff = image.tiffRepresentation,
                  let rep = NSBitmapImageRep(data: tiff),
                  let png = rep.representation(using: .png, properties: [:]) else {
                print("FAIL: cannot render theme \(theme)")
                continue
            }
            let path = "\(outDir)/reader-theme\(theme).png"
            try? png.write(to: URL(fileURLWithPath: path))
            print("wrote", path, "pages:", pages.count, "lines on page 1:", page.lines.count)
        }
        print("OK")
    }
}
