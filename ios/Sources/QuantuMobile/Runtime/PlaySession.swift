import Foundation

/// Runs "Play": resolve the version, download what is missing, build the launch plan, hand it to the JVM runtime.
@MainActor
final class PlaySession: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var stage = ""
    @Published private(set) var fraction: Double = 0
    @Published private(set) var message = ""

    private let runtime: JVMRuntime = MissingJVMRuntime()
    private let paths = GamePaths.default

    func play(versionId: String, account: Account) async {
        guard !busy else { return }
        busy = true; message = ""; fraction = 0; stage = "Preparing"
        defer { busy = false }
        do {
            let manifest = try await MojangAPI.versionManifest()
            let url = manifest.versions.first { $0.id == versionId }?.url
            let version = try await VersionResolver(paths: paths).resolve(id: versionId, manifestURL: url)
            let gameDir = paths.gameDir(versionId)
            try FileManager.default.createDirectory(at: gameDir, withIntermediateDirectories: true)

            try await GameInstaller(paths: paths).install(version, gameDir: gameDir) { stage, done, total in
                Task { @MainActor in
                    self.stage = stage
                    self.fraction = total > 0 ? Double(done) / Double(total) : 0
                }
            }
            stage = "Starting"
            let info = AccountInfo(username: account.username, uuid: account.uuid, accessToken: account.accessToken,
                                   userType: account.userType, xuid: account.xuid)
            let plan = try LaunchPlanner.plan(version: version, account: info, paths: paths, gameDir: gameDir,
                                              width: 1280, height: 720, memoryMb: 1024)
            try await runtime.launch(plan: plan)
        } catch {
            message = error.localizedDescription
        }
        stage = ""
    }
}
