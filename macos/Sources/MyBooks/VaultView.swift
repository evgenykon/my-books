import SwiftUI
import AppKit

/// Settings of the encrypted vaults: GitHub access, the list of vault branches and the
/// "create / open / sync / delete" actions.
struct VaultView: View {
    @ObservedObject var vault: VaultStore
    var onClose: () -> Void

    @State private var token = ""
    @State private var owner = ""
    @State private var repo = ""
    @State private var sheet: VaultSheet?
    @State private var deleting: VaultInfo?

    private let background = Color(nsColor: NSColor(hex: "#F7F7F5"))

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    activeBanner
                    githubSection
                    Divider()
                    vaultSection
                    status
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(width: 640, height: 640)
        .background(background)
        .onAppear {
            owner = vault.owner
            repo = vault.repo
            if vault.configured && vault.vaults.isEmpty { vault.refreshVaults() }
        }
        .onChange(of: vault.passwordPrompt) { _, name in
            guard let name else { return }
            sheet = .unlock(vault.vaults.first { $0.name == name } ?? VaultInfo(name: name))
            vault.passwordPrompt = nil
        }
        .sheet(item: $sheet) { kind in
            switch kind {
            case .create:
                CreateVaultSheet { name, password, confirmation in
                    vault.createVault(name: name, password: password, confirmation: confirmation)
                }
            case .unlock(let info):
                UnlockVaultSheet(info: info) { password in
                    vault.openVault(name: info.name, password: password)
                }
            }
        }
        .alert(
            "Удалить ветку «\(deleting?.name ?? "")»?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
        ) {
            Button("Отмена", role: .cancel) { deleting = nil }
            Button("Удалить", role: .destructive) {
                if let info = deleting { vault.deleteVault(name: info.name) }
                deleting = nil
            }
        } message: {
            Text("Ветка и зашифрованное хранилище в ней будут удалены из репозитория. Локальная библиотека останется.")
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Хранилища").font(.title3.weight(.semibold))
                Text("Зашифрованная копия библиотеки в ветке GitHub")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if vault.active != nil {
                Button {
                    vault.syncNow()
                } label: {
                    Label("Синхронизировать", systemImage: "arrow.triangle.2.circlepath")
                }
                .disabled(vault.busy)
            }
            Button("Готово", action: onClose)
                .keyboardShortcut(.cancelAction)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    // MARK: - Sections

    @ViewBuilder
    private var activeBanner: some View {
        if let active = vault.active {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill").font(.system(size: 11))
                Text("Активное хранилище: \(active) — изменения библиотеки уходят в ветку автоматически")
                    .font(.system(size: 12))
                Spacer(minLength: 0)
                Button("Закрыть") { vault.closeVault() }
                    .buttonStyle(.link)
                    .font(.system(size: 12))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Color(nsColor: NSColor(hex: "#EDE9DC")))
            .cornerRadius(8)
        }
    }

    private var githubSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            VaultSectionTitle("Доступ к GitHub")
            Text("Нужен fine-grained token с правом Contents: Read and write на репозиторий с ветками-хранилищами. "
                + "Токен и пароли лежат в связке ключей macOS.")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            SecureField(
                vault.tokenSaved ? "Токен сохранён — введите новый, чтобы заменить" : "Personal access token",
                text: $token
            )
            .textFieldStyle(.roundedBorder)

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Владелец").font(.system(size: 11)).foregroundStyle(.secondary)
                    TextField("evgenykon", text: $owner)
                        .textFieldStyle(.roundedBorder)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Репозиторий").font(.system(size: 11)).foregroundStyle(.secondary)
                    TextField("my-books", text: $repo)
                        .textFieldStyle(.roundedBorder)
                }
            }

            HStack(spacing: 10) {
                Button("Сохранить") {
                    vault.saveSettings(token: token, owner: owner, repo: repo)
                    token = ""
                }
                .buttonStyle(.borderedProminent)
                .disabled(vault.busy || owner.trimmingCharacters(in: .whitespaces).isEmpty
                    || repo.trimmingCharacters(in: .whitespaces).isEmpty)

                Button {
                    vault.refreshVaults()
                } label: {
                    Label("Обновить список", systemImage: "arrow.clockwise")
                }
                .disabled(!vault.configured || vault.busy)
            }
            .controlSize(.regular)
        }
    }

    private var vaultSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VaultSectionTitle("Ветки-хранилища")
                Spacer()
                Button {
                    sheet = .create
                } label: {
                    Label("Создать ветку", systemImage: "plus")
                }
                .disabled(!vault.configured || vault.busy)
            }

            if !vault.configured {
                Text("Сначала сохраните токен и репозиторий.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else if vault.vaults.isEmpty {
                Text("Хранилищ пока нет. Создайте ветку — в неё загрузятся книги, заметки, прогресс и настройки.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                VStack(spacing: 6) {
                    ForEach(vault.vaults) { info in
                        VaultRow(
                            info: info,
                            isActive: info.name == vault.active,
                            enabled: !vault.busy,
                            onOpen: {
                                if info.passwordSaved {
                                    vault.openVault(name: info.name)
                                } else {
                                    sheet = .unlock(info)
                                }
                            },
                            onDelete: { deleting = info }
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        if vault.busy {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(vault.busyText ?? "Работаю…")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        }
        if let message = vault.message {
            Text(message)
                .font(.system(size: 11))
                .foregroundStyle(Color(nsColor: NSColor(hex: "#3F6B4A")))
                .fixedSize(horizontal: false, vertical: true)
        }
        if let error = vault.error {
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 11))
                Text(error)
                    .font(.system(size: 11))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Button("Скрыть") { vault.error = nil }
                    .buttonStyle(.link)
                    .font(.system(size: 11))
            }
            .foregroundStyle(Color(nsColor: NSColor(hex: "#A03B2F")))
        }
    }
}

// MARK: - Row

private struct VaultRow: View {
    let info: VaultInfo
    let isActive: Bool
    let enabled: Bool
    var onOpen: () -> Void
    var onDelete: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(isActive ? "\(info.name)  · активно" : info.name)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                Text(details)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Button("Открыть", action: onOpen)
                .disabled(!enabled)
            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(.secondary)
                    .frame(width: 22, height: 22)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .help("Удалить ветку")
            .disabled(!enabled)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(nsColor: NSColor(hex: isActive ? "#EDF0E7" : "#FFFFFF")))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.black.opacity(0.07), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var details: String {
        var parts = [info.passwordSaved ? "пароль сохранён" : "пароль не сохранён"]
        if info.books > 0 { parts.append("книг \(info.books)") }
        if info.notes > 0 { parts.append("заметок \(info.notes)") }
        if info.updatedAt > 0 { parts.append(Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(info.updatedAt) / 1000))) }
        return parts.joined(separator: " · ")
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ru_RU")
        formatter.dateFormat = "d MMM yyyy HH:mm"
        return formatter
    }()
}

// MARK: - Sheet: create

private struct CreateVaultSheet: View {
    var onCreate: (String, String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var password = ""
    @State private var confirmation = ""

    private var nameError: String? { name.isEmpty ? nil : VaultNames.validate(name) }
    private var mismatch: Bool { !confirmation.isEmpty && password != confirmation }
    private var canCreate: Bool {
        nameError == nil && !name.isEmpty && password.count >= 6 && password == confirmation
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Новая ветка-хранилище").font(.headline)
            Text("Ветка будет создана в репозитории, книги, заметки, прогресс и настройки загрузятся в неё зашифрованными.")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: 4) {
                Text("Имя (оно же имя ветки)").font(.system(size: 11)).foregroundStyle(.secondary)
                TextField("home-2026", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: name) { _, value in
                        let cleaned = value.trimmingCharacters(in: .whitespaces).lowercased()
                        if cleaned != value { name = cleaned }
                    }
                Text(nameError ?? "Только строчные латинские буквы, цифры и «-», до 40 символов")
                    .font(.system(size: 10))
                    .foregroundStyle(nameError == nil ? Color.secondary : Color(nsColor: NSColor(hex: "#A03B2F")))
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Пароль хранилища").font(.system(size: 11)).foregroundStyle(.secondary)
                SecureField("не короче 6 символов", text: $password)
                    .textFieldStyle(.roundedBorder)
                Text("Не короче 6 символов. Восстановить пароль невозможно.")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Пароль ещё раз").font(.system(size: 11)).foregroundStyle(.secondary)
                SecureField("", text: $confirmation)
                    .textFieldStyle(.roundedBorder)
                if mismatch {
                    Text("Пароли не совпадают")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(nsColor: NSColor(hex: "#A03B2F")))
                }
            }

            HStack {
                Spacer()
                Button("Отмена") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Создать и зашифровать") {
                    onCreate(name, password, confirmation)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!canCreate)
            }
            .padding(.top, 4)
        }
        .padding(24)
        .frame(width: 420)
    }
}

// MARK: - Sheet: unlock

private struct UnlockVaultSheet: View {
    let info: VaultInfo
    var onOpen: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var password = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Открыть «\(info.name)»").font(.headline)
            Text(info.passwordSaved
                ? "Пароль сохранён в связке ключей. Оставьте поле пустым, чтобы использовать его."
                : "Введите пароль хранилища. Он сохранится в связке ключей macOS и больше спрашиваться не будет.")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            SecureField(info.passwordSaved ? "Пароль (можно оставить пустым)" : "Пароль", text: $password)
                .textFieldStyle(.roundedBorder)

            HStack {
                Spacer()
                Button("Отмена") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Открыть") {
                    onOpen(password.trimmingCharacters(in: .whitespaces))
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!info.passwordSaved && password.isEmpty)
            }
            .padding(.top, 4)
        }
        .padding(24)
        .frame(width: 400)
    }
}

// MARK: - Helpers

/// One sheet at a time; two `.sheet` modifiers on the same view are fragile on macOS.
private enum VaultSheet: Identifiable {
    case create
    case unlock(VaultInfo)

    var id: String {
        switch self {
        case .create: return "create"
        case .unlock(let info): return "unlock-\(info.name)"
        }
    }
}

private struct VaultSectionTitle: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(.secondary)
    }
}
