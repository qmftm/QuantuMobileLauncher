import Foundation

/// Where the launcher keeps its files (inside the app's Documents folder, so the Files app can show it).
struct GamePaths {
    let root: URL
    var libraries: URL { root.appendingPathComponent("libraries") }
    var assets: URL { root.appendingPathComponent("assets") }
    var versions: URL { root.appendingPathComponent("versions") }
    func versionJar(_ id: String) -> URL { versions.appendingPathComponent("\(id)/\(id).jar") }
    func versionJSON(_ id: String) -> URL { versions.appendingPathComponent("\(id)/\(id).json") }
    func gameDir(_ name: String) -> URL { root.appendingPathComponent("instances/\(name)") }

    static var `default`: GamePaths {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return GamePaths(root: docs.appendingPathComponent("QuantuMobile"))
    }
}

/// Downloads everything a resolved version needs: client jar, libraries, assets and logging config.
struct GameInstaller {
    let paths: GamePaths
    var env = RuleEnv()

    func install(_ version: VersionInfo, gameDir: URL, progress: @escaping @Sendable (String, Int, Int) -> Void) async throws {
        progress("Client", 0, 1)
        let jar = paths.versionJar(version.jarId)
        if let client = version.clientDownload {
            try await Downloader.download(DownloadTask(url: client.url, dest: jar, sha1: client.sha1, size: client.size))
        } else if !FileManager.default.fileExists(atPath: jar.path) {
            throw GameError("No client jar for \(version.jarId)")
        }

        try await Downloader.downloadAll(try libraryTasks(version)) { d, t in progress("Libraries", d, t) }
        try await installAssets(version, gameDir: gameDir, progress: progress)

        if let log = version.logging {
            let dest = paths.assets.appendingPathComponent("log_configs/\(log.id)")
            try await Downloader.download(DownloadTask(url: log.url, dest: dest, sha1: log.sha1, size: log.size))
        }
        progress("Done", 1, 1)
    }

    func libraryTasks(_ version: VersionInfo) throws -> [DownloadTask] {
        try Self.classpathLibraries(version, env: env).map { lib in
            DownloadTask(url: lib.downloadURL, dest: paths.libraries.appendingPathComponent(lib.path),
                         sha1: lib.artifact?.sha1, size: lib.artifact?.size ?? -1)
        }
    }

    private func installAssets(_ version: VersionInfo, gameDir: URL, progress: @escaping @Sendable (String, Int, Int) -> Void) async throws {
        guard let ref = version.assetIndex else { return }
        let indexFile = paths.assets.appendingPathComponent("indexes/\(ref.id).json")
        try await Downloader.download(DownloadTask(url: ref.url, dest: indexFile, sha1: ref.sha1, size: ref.size))
        let index = try AssetIndex.parse(try Data(contentsOf: indexFile))

        let tasks = index.objects.map {
            DownloadTask(url: AssetIndex.resourcesURL + $0.objectPath, dest: paths.assets.appendingPathComponent("objects/\($0.objectPath)"),
                         sha1: $0.hash, size: $0.size)
        }
        try await Downloader.downloadAll(tasks, parallelism: 16) { d, t in progress("Assets", d, t) }

        // Pre-1.7 versions read assets from a flat directory instead of the hashed object store.
        if index.isVirtual || index.mapToResources {
            let target = index.mapToResources ? gameDir.appendingPathComponent("resources") : paths.assets.appendingPathComponent("virtual/\(ref.id)")
            let fm = FileManager.default
            for obj in index.objects {
                let dest = target.appendingPathComponent(obj.path)
                try fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                if !fm.fileExists(atPath: dest.path) {
                    try fm.copyItem(at: paths.assets.appendingPathComponent("objects/\(obj.objectPath)"), to: dest)
                }
            }
        }
    }

    /// Libraries that go on the classpath, in order, de-duplicated by maven key (first wins, so mod loader overrides beat
    /// vanilla). Desktop natives are dropped.
    static func classpathLibraries(_ version: VersionInfo, env: RuleEnv) throws -> [Library] {
        var seen = Set<String>()
        return try version.libraries().filter { lib in
            Rules.isAllowed(lib.rules, env: env) && !lib.isDesktopNative && seen.insert(lib.coords.key).inserted
        }
    }
}
