import SwiftUI
import AppKit

@main
struct MyBooksApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store = LibraryStore()
    @State private var openRecord: BookRecord?

    var body: some Scene {
        WindowGroup("My Books") {
            RootView(store: store, openRecord: $openRecord)
                .frame(minWidth: 900, minHeight: 620)
        }
        .defaultSize(width: 1040, height: 720)
        .windowToolbarStyle(.unifiedCompact)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .textEditing) {
                Button("Добавить книгу…") { NotificationCenter.default.post(name: .myBooksImport, object: nil) }
                    .keyboardShortcut("o")
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        let books = filenames.filter { $0.lowercased().hasSuffix(".fb2") }
        DebugLog.log("open files: \(filenames) -> \(books)")
        if let first = books.first {
            NotificationCenter.default.post(name: .myBooksOpenFile, object: first)
            sender.reply(toOpenOrPrint: .success)
        } else {
            sender.reply(toOpenOrPrint: books.isEmpty && filenames.isEmpty ? .failure : .success)
        }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        DispatchQueue.main.async {
            guard let window = NSApp.windows.first else { return }
            let screens = NSScreen.screens
            let onScreen = screens.contains { $0.visibleFrame.intersects(window.frame) }
            if !onScreen, let screen = NSScreen.main {
                let visible = screen.visibleFrame
                let size = window.frame.size
                window.setFrameOrigin(NSPoint(
                    x: visible.midX - size.width / 2,
                    y: visible.midY - size.height / 2
                ))
            }
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}

extension Notification.Name {
    static let myBooksImport = Notification.Name("myBooksImport")
    static let myBooksOpenFile = Notification.Name("myBooksOpenFile")
}

struct RootView: View {
    @ObservedObject var store: LibraryStore
    @Binding var openRecord: BookRecord?

    var body: some View {
        ZStack {
            if let record = openRecord {
                ReaderView(record: record, store: store) {
                    openRecord = nil
                }
                .transition(.opacity)
            } else {
                LibraryView(store: store) { record in
                    openRecord = record
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.16), value: openRecord)
        .onReceive(NotificationCenter.default.publisher(for: .myBooksOpenFile)) { note in
            guard let path = note.object as? String else { return }
            openLocalBook(at: path)
        }
        .task {
            // `open MyBooks.app --args /path/book.fb2` imports and opens the book.
            guard openRecord == nil else { return }
            let candidates = ProcessInfo.processInfo.arguments.dropFirst()
            guard let path = candidates.first(where: { FileManager.default.fileExists(atPath: $0) }) else {
                DebugLog.log("launch: no book argument")
                return
            }
            openLocalBook(at: path)
        }
        .alert("Ошибка", isPresented: Binding(
            get: { store.lastError != nil },
            set: { if !$0 { store.lastError = nil } }
        )) {
            Button("OK") { store.lastError = nil }
        } message: {
            Text(store.lastError ?? "")
        }
    }

    /// Imports (if needed) and opens a book file that came from Finder or `open`.
    private func openLocalBook(at path: String) {
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: path) else {
            DebugLog.log("open: missing file \(path)")
            return
        }
        DebugLog.log("open: \(url.lastPathComponent)")
        let known = store.books.first { $0.fileName == url.lastPathComponent }
        if known == nil {
            let accessed = url.startAccessingSecurityScopedResource()
            store.importBook(at: url)
            if accessed { url.stopAccessingSecurityScopedResource() }
        }
        if let record = store.books.first(where: { $0.fileName == url.lastPathComponent }) ?? store.books.first {
            openRecord = record
        }
    }
}
