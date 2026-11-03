import Foundation

struct AssetObject {
    let path: String
    let hash: String
    let size: Int64
    var objectPath: String { "\(hash.prefix(2))/\(hash)" }
}

struct AssetIndex {
    static let resourcesURL = "https://resources.download.minecraft.net/"

    let objects: [AssetObject]
    let isVirtual: Bool
    let mapToResources: Bool

    static func parse(_ data: Data) throws -> AssetIndex {
        guard let root = try JSONSerialization.jsonObject(with: data) as? JSON else { throw GameError("Bad asset index") }
        let objs = (root["objects"] as? JSON ?? [:]).compactMap { path, value -> AssetObject? in
            guard let o = value as? JSON, let hash = o["hash"] as? String else { return nil }
            return AssetObject(path: path, hash: hash, size: (o["size"] as? NSNumber)?.int64Value ?? -1)
        }.sorted { $0.path < $1.path }
        return AssetIndex(objects: objs, isVirtual: root["virtual"] as? Bool == true, mapToResources: root["map_to_resources"] as? Bool == true)
    }
}
