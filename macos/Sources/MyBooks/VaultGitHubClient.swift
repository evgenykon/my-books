import Foundation

/// A file read from GitHub together with the blob sha needed to update or delete it.
struct RemoteFile: Equatable {
    let content: Data
    let sha: String
}

enum VaultError: LocalizedError, Equatable {
    /// Message already written for the user.
    case message(String)
    /// The branch moved under us; the caller re-reads and retries.
    case conflict

    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        case .conflict: return "Ветка изменилась, повторите"
        }
    }
}

/// Minimal GitHub REST client for vault branches: contents, git refs and blobs.
///
/// A vault is one branch, so the client only needs the five calls the vault uses. The
/// Contents API inlines base64 for files up to 1 MB; larger blobs (FB2 books) are fetched
/// through the Git data API by sha.
final class VaultGitHubClient {
    private struct APIResponse {
        let status: Int
        let data: Data?
    }

    private let token: String
    private let owner: String
    private let repo: String
    private let session: URLSession

    init(token: String, owner: String, repo: String) {
        self.token = Self.sanitize(token)
        self.owner = Self.sanitize(owner)
        self.repo = Self.sanitize(repo)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 300
        configuration.waitsForConnectivity = false
        self.session = URLSession(configuration: configuration)
    }

    var configured: Bool { !token.isEmpty && !owner.isEmpty && !repo.isEmpty }

    private var api: String { "https://api.github.com/repos/\(owner)/\(repo)" }

    // MARK: - Branches

    func listBranches() async throws -> [String] {
        let response = try await send("GET", "\(api)/branches?per_page=100")
        if response.status == 404 {
            throw VaultError.message("Репозиторий \(owner)/\(repo) не найден или у токена нет к нему доступа")
        }
        guard (200..<300).contains(response.status),
              let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let objects = json as? [[String: Any]]
        else {
            throw failure(response.status, response.data)
        }
        return objects.compactMap { $0["name"] as? String }
    }

    /// Branches that already hold a `vault.enc`.
    func listVaults() async throws -> [String] {
        let branches = try await listBranches()
        let defaultBranch = (try? await repositoryDefaultBranch()) ?? "main"
        var names: [String] = []
        for branch in branches where branch != defaultBranch {
            if try await hasVault(branch) { names.append(branch) }
        }
        return names
    }

    func hasVault(_ branch: String) async throws -> Bool {
        let file = try await readFile(path: VaultConst.vaultFile, branch: branch)
        return file != nil
    }

    func headSha(_ branch: String) async throws -> String? {
        let response = try await send("GET", "\(api)/git/ref/heads/\(encode(branch))")
        if response.status == 404 { return nil }
        guard (200..<300).contains(response.status),
              let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let object = json as? [String: Any],
              let ref = object["object"] as? [String: Any]
        else {
            throw failure(response.status, response.data)
        }
        return ref["sha"] as? String
    }

    func repositoryDefaultBranch() async throws -> String? {
        let response = try await send("GET", api)
        guard (200..<300).contains(response.status),
              let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let object = json as? [String: Any]
        else {
            return nil
        }
        return object["default_branch"] as? String
    }

    /// Creates the branch from the head of the default branch.
    func createBranch(_ name: String) async throws {
        var base = "main"
        var sha = try await headSha(base)
        if sha == nil, let fallback = try await repositoryDefaultBranch() {
            base = fallback
            sha = try await headSha(base)
        }
        guard let sha else {
            throw VaultError.message("Не удалось прочитать ветку \(base): проверьте, что она существует")
        }
        let response = try await send(
            "POST",
            "\(api)/git/refs",
            body: ["ref": "refs/heads/\(name)", "sha": sha]
        )
        if response.status == 422 {
            throw VaultError.message("Ветка «\(name)» уже существует")
        }
        guard (200..<300).contains(response.status) else {
            throw failure(response.status, response.data)
        }
    }

    func deleteBranch(_ name: String) async throws {
        let response = try await send("DELETE", "\(api)/git/refs/heads/\(encode(name))")
        // An already removed branch is not an error.
        if response.status == 404 { return }
        guard (200..<300).contains(response.status) else {
            throw failure(response.status, response.data)
        }
    }

    // MARK: - Files

    func readFile(path: String, branch: String) async throws -> RemoteFile? {
        let response = try await send("GET", "\(api)/contents/\(encodePath(path))?ref=\(encode(branch))")
        if response.status == 404 { return nil }
        guard (200..<300).contains(response.status),
              let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let object = json as? [String: Any]
        else {
            throw failure(response.status, response.data)
        }

        let sha = object["sha"] as? String ?? ""
        let encoding = object["encoding"] as? String ?? ""
        let inline = object["content"] as? String ?? ""

        guard encoding != "none" && !inline.isEmpty else {
            // File is bigger than 1 MB and comes without an inline body.
            guard !sha.isEmpty else { return nil }
            return try await readBlob(sha: sha)
        }
        guard let content = Self.decodeBase64(inline) else {
            throw VaultError.message("GitHub вернул файл «\(path)» в неизвестном виде")
        }
        return RemoteFile(content: content, sha: sha)
    }

    private func readBlob(sha: String) async throws -> RemoteFile? {
        let response = try await send("GET", "\(api)/git/blobs/\(sha)")
        if response.status == 404 { return nil }
        guard (200..<300).contains(response.status),
              let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let object = json as? [String: Any],
              let content = object["content"] as? String,
              let decoded = Self.decodeBase64(content)
        else {
            throw failure(response.status, response.data)
        }
        return RemoteFile(content: decoded, sha: sha)
    }

    @discardableResult
    func writeFile(
        path: String,
        branch: String,
        content: Data,
        message: String,
        sha: String?
    ) async throws -> String? {
        var body: [String: Any] = [
            "message": message,
            "content": content.base64EncodedString(),
            "branch": branch,
        ]
        if let sha { body["sha"] = sha }

        let response = try await send("PUT", "\(api)/contents/\(encodePath(path))", body: body)
        if response.status == 409 || response.status == 422 { throw VaultError.conflict }
        if response.status == 404 {
            throw VaultError.message("Ветка «\(branch)» не найдена в репозитории")
        }
        guard (200..<300).contains(response.status) else {
            throw failure(response.status, response.data)
        }
        guard let json = response.data.flatMap({ try? JSONSerialization.jsonObject(with: $0) }),
              let object = json as? [String: Any],
              let saved = object["content"] as? [String: Any]
        else {
            return nil
        }
        return saved["sha"] as? String
    }

    func deleteFile(path: String, branch: String, message: String) async throws {
        guard let existing = try await readFile(path: path, branch: branch) else { return }
        let response = try await send(
            "DELETE",
            "\(api)/contents/\(encodePath(path))",
            body: ["message": message, "sha": existing.sha, "branch": branch]
        )
        if response.status == 404 { return }
        if response.status == 409 || response.status == 422 { throw VaultError.conflict }
        guard (200..<300).contains(response.status) else {
            throw failure(response.status, response.data)
        }
    }

    // MARK: - HTTP

    private func send(_ method: String, _ urlString: String, body: [String: Any]? = nil) async throws -> APIResponse {
        guard configured else {
            throw VaultError.message("Укажите токен, владельца и репозиторий в настройках GitHub")
        }
        guard let url = URL(string: urlString) else {
            throw VaultError.message("Некорректный адрес GitHub API")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        request.setValue("MyBooks-macOS", forHTTPHeaderField: "User-Agent")

        if let body {
            do {
                request.httpBody = try JSONSerialization.data(withJSONObject: body)
            } catch {
                throw VaultError.message("Не удалось подготовить запрос к GitHub")
            }
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }

        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return APIResponse(status: status, data: data)
        } catch {
            throw VaultError.message("Нет связи с GitHub: \(error.localizedDescription)")
        }
    }

    private func failure(_ status: Int, _ data: Data?) -> VaultError {
        if status == 401 {
            return .message(
                "GitHub отклонил токен (401). Проверьте, что токен действует и имеет доступ к \(owner)/\(repo)."
            )
        }
        if status == 403 {
            let detail = message(from: data) ?? "нужен токен с правом Contents: Read and write"
            return .message("GitHub запретил операцию (403): \(detail)")
        }
        if status == 0 {
            return .message("GitHub не ответил. Проверьте подключение к сети.")
        }
        if let detail = message(from: data) {
            return .message("GitHub \(status): \(detail)")
        }
        return .message("GitHub \(status): операция не выполнена")
    }

    /// Human readable `message` field of a GitHub error body.
    private func message(from data: Data?) -> String? {
        guard let data = data,
              let json = try? JSONSerialization.jsonObject(with: data),
              let object = json as? [String: Any],
              let text = object["message"] as? String, !text.isEmpty
        else {
            return nil
        }
        return String(text.prefix(200))
    }

    private func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? value
    }

    private func encodePath(_ path: String) -> String {
        path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/")
            .map { encode(String($0)) }
            .joined(separator: "/")
    }

    /// GitHub returns base64 with line breaks; they are not part of the standard alphabet.
    private static func decodeBase64(_ value: String) -> Data? {
        let cleaned = value.replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .trimmingCharacters(in: .whitespaces)
        return Data(base64Encoded: cleaned)
    }

    /// Keeps only printable ASCII (0x21..0x7E): stray spaces and non-ASCII characters copied
    /// from notes make URLSession reject the request, or GitHub return 401.
    private static func sanitize(_ value: String) -> String {
        String(value.unicodeScalars.filter { (0x21...0x7E).contains(Int($0.value)) }.map { Character($0) })
    }
}
