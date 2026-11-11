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
        .task {
            // `open MyBooks.app --args /path/book.fb2` imports and opens the book.
            guard openRecord == nil else { return }
            let candidates = ProcessInfo.processInfo.arguments.dropFirst()
            guard let path = candidates.first(where: { FileManager.default.fileExists(atPath: $0) }) else {
                DebugLog.log("launch: no book argument")
                return
            }
            let url = URL(fileURLWithPath: path)
            DebugLog.log("launch: opening \(url.lastPathComponent)")
            let existing = store.books.first { $0.fileName == url.lastPathComponent }
            if existing == nil {
                store.importBook(at: url)
            }
            if let record = store.books.first(where: { $0.fileName == url.lastPathComponent }) ?? store.books.first {
                openRecord = record
            }
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
}
