import SwiftUI
import AppKit

/// Small file logger so behaviour can be inspected without a console window.
enum DebugLog {
    static let url: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("MyBooks", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("debug.log")
    }()

    static func log(_ message: String) {
        let line = "[\(timestamp())] \(message)\n"
        FileHandle.standardError.write(Data(line.utf8))
        guard let data = line.data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            try? data.write(to: url)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}

/// Bridges AppKit scroll-wheel / key events into page turns.
///
/// SwiftUI key handling needs focus and text selection swallows drag gestures, so the
/// events are observed at the window level and translated into next/previous page calls.
struct PagingEventBridge: NSViewRepresentable {
    var onNext: () -> Void
    var onPrevious: () -> Void

    func makeNSView(context: Context) -> BridgeView {
        let view = BridgeView()
        view.onNext = onNext
        view.onPrevious = onPrevious
        return view
    }

    func updateNSView(_ nsView: BridgeView, context: Context) {
        nsView.onNext = onNext
        nsView.onPrevious = onPrevious
    }

    final class BridgeView: NSView {
        var onNext: () -> Void = {}
        var onPrevious: () -> Void = {}

        private var scrollMonitor: Any?
        private var keyMonitor: Any?
        private var accumulated: CGFloat = 0
        private var lastFlip = Date.distantPast

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if window != nil {
                installMonitors()
            } else {
                removeMonitors()
            }
        }

        deinit {
            removeMonitors()
        }

        private func installMonitors() {
            guard scrollMonitor == nil else { return }
            scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
                guard let self, let window = self.window, event.window === window else { return event }
                return self.handleScroll(event) ? nil : event
            }
            keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self, let window = self.window, event.window === window else { return event }
                return self.handleKey(event) ? nil : event
            }
            DebugLog.log("paging: event monitors installed, bounds=\(bounds)")
        }

        private func removeMonitors() {
            if let scrollMonitor { NSEvent.removeMonitor(scrollMonitor) }
            if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
            scrollMonitor = nil
            keyMonitor = nil
        }

        private func pointerInside(_ event: NSEvent) -> Bool {
            let point = convert(event.locationInWindow, from: nil)
            return bounds.contains(point)
        }

        private func handleScroll(_ event: NSEvent) -> Bool {
            guard pointerInside(event) else { return false }
            let dx = event.scrollingDeltaX
            let dy = event.scrollingDeltaY
            let delta = abs(dx) > abs(dy) ? dx : dy
            if delta == 0 { return false }
            accumulated += delta
            let threshold: CGFloat = event.hasPreciseScrollingDeltas ? 70 : 5
            let now = Date()
            if abs(accumulated) >= threshold, now.timeIntervalSince(lastFlip) > 0.25 {
                if accumulated < 0 { onNext() } else { onPrevious() }
                accumulated = 0
                lastFlip = now
            }
            return true
        }

        private func handleKey(_ event: NSEvent) -> Bool {
            if !event.modifierFlags.intersection([.command, .option, .control]).isEmpty { return false }
            if let responder = window?.firstResponder, responder is NSText { return false }
            switch event.keyCode {
            case 123: onPrevious(); return true   // ←
            case 124: onNext(); return true       // →
            case 116: onPrevious(); return true   // page up
            case 121: onNext(); return true       // page down
            case 49: onNext(); return true        // space
            default: return false
            }
        }
    }
}
