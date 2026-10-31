import Foundation

struct VersionEntry: Identifiable, Decodable, Hashable {
    let id: String
    let type: String
    let url: String
    let releaseTime: String
}

struct VersionManifest: Decodable {
    struct Latest: Decodable { let release: String; let snapshot: String }
    let latest: Latest
    let versions: [VersionEntry]
}

enum MojangAPI {
    static let manifestURL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    static func versionManifest() async throws -> VersionManifest {
        let res = try await Http.get(manifestURL)
        guard res.isSuccess else { throw AuthError(message: "Version list failed (HTTP \(res.code))") }
        return try JSONDecoder().decode(VersionManifest.self, from: res.body)
    }
}
