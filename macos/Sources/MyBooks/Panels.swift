import SwiftUI
import AppKit
import Shared

// MARK: - Table of contents

struct TocPanel: View {
    let entries: [TocEntry]
    let palette: ReaderPalette
    let currentBlock: Int
    var onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Содержание")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 8)

            if entries.isEmpty {
                Text("В этой книге нет оглавления")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .padding(16)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                            Button {
                                onSelect(Int(entry.blockIndex))
                            } label: {
                                HStack(spacing: 6) {
                                    Text(entry.title)
                                        .font(.system(size: entry.level == 1 ? 13 : 12, weight: entry.level == 1 ? .medium : .regular))
                                        .lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                    Spacer(minLength: 0)
                                }
                                .padding(.leading, 16 + CGFloat(max(Int(entry.level) - 1, 0)) * 14)
                                .padding(.trailing, 12)
                                .padding(.vertical, 6)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.bottom, 16)
                }
            }
        }
        .background(Color(nsColor: NSColor(hex: palette.background)))
    }
}

// MARK: - Notes

struct NotesPanel: View {
    let notes: [ReaderNote]
    let palette: ReaderPalette
    var onSelect: (ReaderNote) -> Void
    var onDelete: (ReaderNote) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Заметки")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 8)

            if notes.isEmpty {
                Text("Заметок пока нет. Выделите абзац, нажмите правую кнопку — «Добавить заметку».")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(16)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(notes) { note in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(note.text)
                                    .font(.system(size: 12))
                                    .fixedSize(horizontal: false, vertical: true)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                if !note.quote.isEmpty && note.quote != note.text {
                                    Text("«" + String(note.quote.prefix(160)) + "»")
                                        .font(.system(size: 11))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(3)
                                }
                                HStack(spacing: 10) {
                                    Button("Перейти") { onSelect(note) }
                                        .buttonStyle(.link)
                                        .font(.system(size: 11))
                                    Button("Удалить") { onDelete(note) }
                                        .buttonStyle(.link)
                                        .font(.system(size: 11))
                                }
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(nsColor: NSColor(hex: palette.secondary)).opacity(0.07))
                            .cornerRadius(6)
                            .padding(.horizontal, 12)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .background(Color(nsColor: NSColor(hex: palette.background)))
    }
}

// MARK: - Settings

struct SettingsPanel: View {
    @State var settings: ReaderSettings
    var onChange: (ReaderSettings) -> Void

    private let families = Paginator.familyNames
    private let themes = ["Светлая", "Сепия", "Тёмная"]

    var body: some View {
        let palette = ReaderPalette.of(settings)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Настройки чтения")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)

                Text("Шрифт").font(.system(size: 11)).foregroundStyle(.secondary)
                Picker("", selection: Binding(
                    get: { settings.fontFamily },
                    set: { settings.fontFamily = $0; onChange(settings) }
                )) {
                    ForEach(0..<families.count, id: \.self) { index in
                        Text(families[index]).tag(index)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()

                sliderRow(
                    "Размер шрифта",
                    value: Binding(
                        get: { settings.fontSize },
                        set: { settings.fontSize = $0; onChange(settings) }
                    ),
                    range: 10...34,
                    format: { String(format: "%.0f", $0) }
                )

                sliderRow(
                    "Высота строки",
                    value: Binding(
                        get: { settings.lineHeight },
                        set: { settings.lineHeight = $0; onChange(settings) }
                    ),
                    range: 1.0...2.2,
                    format: { String(format: "%.2f", $0) }
                )

                sliderRow(
                    "Поля",
                    value: Binding(
                        get: { settings.marginH },
                        set: { settings.marginH = $0; onChange(settings) }
                    ),
                    range: 16...140,
                    format: { String(format: "%.0f", $0) }
                )

                Text("Тема").font(.system(size: 11)).foregroundStyle(.secondary)
                Picker("", selection: Binding(
                    get: { settings.theme },
                    set: { settings.theme = $0; onChange(settings) }
                )) {
                    ForEach(0..<themes.count, id: \.self) { index in
                        Text(themes[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                Toggle("Выравнивание по ширине", isOn: Binding(
                    get: { settings.justified },
                    set: { settings.justified = $0; onChange(settings) }
                ))
                .font(.system(size: 12))

                Button("Сбросить") {
                    settings = ReaderSettings()
                    onChange(settings)
                }
                .font(.system(size: 12))

                Spacer(minLength: 0)
            }
            .padding(16)
        }
        .background(Color(nsColor: NSColor(hex: palette.background)))
    }

    private func sliderRow(
        _ title: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        format: (Double) -> String
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title).font(.system(size: 11)).foregroundStyle(.secondary)
                Spacer()
                Text(format(value.wrappedValue)).font(.system(size: 11)).monospacedDigit()
            }
            Slider(value: value, in: range)
        }
    }
}
