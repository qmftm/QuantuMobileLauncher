import Foundation

/// Loads a version's JSON (from the manifest URL the first time, then from disk) and resolves `inheritsFrom` chains.
struct VersionResolver {
    let paths: GamePaths
    /// Fetches a URL's bytes; replaceable in tests.
    var fetch: (String) async throws -> Data = { url in
        guard let u = URL(string: url) else { throw GameError("Bad URL: \(url)") }
        let (data, response) = try await URLSession.shared.data(from: u)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw GameError("HTTP \(code): \(url)") }
        return data
    }

    /// `manifestURL` is only needed when the version is not on disk yet.
    func resolve(id: String, manifestURL: String?) async throws -> VersionInfo {
        let json = try await load(id: id, manifestURL: manifestURL)
        if let parentId = json["inheritsFrom"] as? String {
            let parent = try await resolve(id: parentId, manifestURL: nil)
            return try VersionInfo.merge(child: json, parent: parent)
        }
        return try VersionInfo.parse(json)
    }

    private func load(id: String, manifestURL: String?) async throws -> JSON {
        let file = paths.versionJSON(id)
        if let data = try? Data(contentsOf: file), let json = try JSONSerialization.jsonObject(with: data) as? JSON { return json }
        guard let manifestURL else { throw GameError("Version \(id) is not installed and has no download address.") }
        let data = try await fetch(manifestURL)
        guard let json = try JSONSerialization.jsonObject(with: data) as? JSON else { throw GameError("Bad version JSON for \(id)") }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
        return json
    }
}
