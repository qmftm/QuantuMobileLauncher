import Foundation

struct Artifact: Equatable {
    var path: String?
    var url: String
    var sha1: String?
    var size: Int64

    init(path: String? = nil, url: String, sha1: String? = nil, size: Int64 = -1) {
        self.path = path; self.url = url; self.sha1 = sha1; self.size = size
    }

    init(_ o: JSON) {
        self.init(path: o["path"] as? String, url: (o["url"] as? String) ?? "", sha1: o["sha1"] as? String,
                  size: (o["size"] as? NSNumber)?.int64Value ?? -1)
    }
}

/// Maven coordinate `group:artifact:version[:classifier][@extension]`.
struct MavenCoords: Equatable {
    var group: String
    var artifact: String
    var version: String
    var classifier: String?
    var ext: String

    /// Identity used to de-duplicate libraries (same artifact in different versions).
    var key: String { ([group, artifact] + (classifier.map { [$0] } ?? [])).joined(separator: ":") }

    var path: String {
        var file = "\(artifact)-\(version)"
        if let classifier { file += "-\(classifier)" }
        return "\(group.replacingOccurrences(of: ".", with: "/"))/\(artifact)/\(version)/\(file).\(ext)"
    }

    static func parse(_ name: String) throws -> MavenCoords {
        let halves = name.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        let parts = halves[0].split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3 else { throw GameError("Invalid maven coordinate: \(name)") }
        return MavenCoords(group: parts[0], artifact: parts[1], version: parts[2],
                           classifier: parts.count > 3 ? parts[3] : nil, ext: halves.count > 1 ? halves[1] : "jar")
    }
}

struct Library {
    static let defaultRepo = "https://libraries.minecraft.net/"

    var name: String
    var coords: MavenCoords
    var artifact: Artifact?
    var mavenRepo: String?
    var rules: [Any]?
    /// Old-style entries that only carry per-OS native classifiers.
    var nativesOnly: Bool

    var path: String { artifact?.path ?? coords.path }

    /// Desktop native jars (`natives-macos` etc.). They are not loaded from the classpath.
    var isDesktopNative: Bool { nativesOnly || (coords.classifier?.hasPrefix("natives") ?? false) }

    var downloadURL: String {
        if let url = artifact?.url, !url.isEmpty { return url }
        var repo = mavenRepo ?? Library.defaultRepo
        while repo.hasSuffix("/") { repo.removeLast() }
        return repo + "/" + coords.path
    }

    static func parse(_ o: JSON) throws -> Library {
        guard let name = o["name"] as? String else { throw GameError("Library without name") }
        let artifact = ((o["downloads"] as? JSON)?["artifact"] as? JSON).map(Artifact.init)
        return Library(name: name, coords: try MavenCoords.parse(name), artifact: artifact, mavenRepo: o["url"] as? String,
                       rules: o["rules"] as? [Any], nativesOnly: o["natives"] != nil && artifact == nil)
    }
}

struct GameError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
