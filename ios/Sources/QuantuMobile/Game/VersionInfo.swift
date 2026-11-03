import Foundation

struct AssetIndexRef { let id: String; let url: String; let sha1: String?; let size: Int64 }
struct LoggingRef { let argument: String; let id: String; let url: String; let sha1: String?; let size: Int64 }

/// A fully resolved version (after `inheritsFrom` merging). `jarId` names the version whose client jar is used
/// (for mod loader profiles this is the vanilla parent).
struct VersionInfo {
    let id: String
    let json: JSON
    let jarId: String

    var type: String { json["type"] as? String ?? "release" }
    var mainClass: String { json["mainClass"] as? String ?? "" }

    var assetIndex: AssetIndexRef? {
        guard let o = json["assetIndex"] as? JSON, let id = o["id"] as? String, let url = o["url"] as? String else { return nil }
        return AssetIndexRef(id: id, url: url, sha1: o["sha1"] as? String, size: (o["size"] as? NSNumber)?.int64Value ?? -1)
    }

    var assets: String { json["assets"] as? String ?? assetIndex?.id ?? "legacy" }

    var clientDownload: Artifact? {
        ((json["downloads"] as? JSON)?["client"] as? JSON).map(Artifact.init)
    }

    /// Major Java version required; very old manifests omit it and run on Java 8.
    var javaMajorVersion: Int { ((json["javaVersion"] as? JSON)?["majorVersion"] as? NSNumber)?.intValue ?? 8 }

    func libraries() throws -> [Library] { try (json["libraries"] as? [JSON] ?? []).map(Library.parse) }

    var gameArguments: [Any]? { (json["arguments"] as? JSON)?["game"] as? [Any] }
    var jvmArguments: [Any]? { (json["arguments"] as? JSON)?["jvm"] as? [Any] }
    var legacyArguments: String? { json["minecraftArguments"] as? String }

    var logging: LoggingRef? {
        guard let client = (json["logging"] as? JSON)?["client"] as? JSON, let file = client["file"] as? JSON,
              let argument = client["argument"] as? String, let id = file["id"] as? String, let url = file["url"] as? String
        else { return nil }
        return LoggingRef(argument: argument, id: id, url: url, sha1: file["sha1"] as? String,
                          size: (file["size"] as? NSNumber)?.int64Value ?? -1)
    }

    static func parse(_ json: JSON) throws -> VersionInfo {
        guard let id = json["id"] as? String else { throw GameError("Version JSON without id") }
        return VersionInfo(id: id, json: json, jarId: json["jar"] as? String ?? id)
    }

    /// Merges a child profile (e.g. Fabric) onto its resolved parent, following launcher semantics.
    static func merge(child: JSON, parent: VersionInfo) throws -> VersionInfo {
        var merged = parent.json
        for (key, value) in child {
            switch key {
            case "inheritsFrom": break
            case "libraries": // child libraries first so they win classpath de-duplication
                merged[key] = (value as? [Any] ?? []) + (parent.json["libraries"] as? [Any] ?? [])
            case "arguments":
                let parentArgs = parent.json["arguments"] as? JSON ?? [:]
                let childArgs = value as? JSON ?? [:]
                var out: JSON = [:]
                for k in Set(parentArgs.keys).union(childArgs.keys) {
                    out[k] = (parentArgs[k] as? [Any] ?? []) + (childArgs[k] as? [Any] ?? [])
                }
                merged[key] = out
            default: merged[key] = value
            }
        }
        guard let childId = child["id"] as? String else { throw GameError("Version JSON without id") }
        let hasClient = ((child["downloads"] as? JSON)?["client"]) != nil
        let jarId = child["jar"] as? String ?? (hasClient ? childId : parent.jarId)
        return VersionInfo(id: childId, json: merged, jarId: jarId)
    }
}
