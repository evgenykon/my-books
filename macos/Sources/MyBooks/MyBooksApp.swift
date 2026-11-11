import SwiftUI
import AppKit

@main
struct MyBooksApp: App {
    @StateObject private var store = LibraryStore()
    @State private var openRecord: BookRecord?

    var body: some Scene {
        WindowGroup("My Books") {
            RootView(store: store, openRecord: $openRecord)
                .frame(minWidth: 900, minHeight: 620)
        }
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
