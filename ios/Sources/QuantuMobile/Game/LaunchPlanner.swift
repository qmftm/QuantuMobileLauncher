import Foundation

/// A launch ready to hand to a JVM: the `java` arguments (JVM options, main class, game options) and the folders involved.
struct LaunchPlan {
    let args: [String]
    let gameDir: URL
    let nativesDir: URL
    let classpath: [String]
    let javaMajorVersion: Int
}

enum LaunchPlanner {
    /// Builds the launch plan for an installed version: classpath = libraries (in order) + the client jar.
    static func plan(version: VersionInfo, account: AccountInfo, paths: GamePaths, gameDir: URL,
                     width: Int, height: Int, memoryMb: Int, env: RuleEnv = RuleEnv()) throws -> LaunchPlan {
        let libs = try GameInstaller.classpathLibraries(version, env: env)
        let classpath = libs.map { paths.libraries.appendingPathComponent($0.path).path } + [paths.versionJar(version.jarId).path]
        let natives = gameDir.appendingPathComponent("natives")
        let logPath = version.logging.map { paths.assets.appendingPathComponent("log_configs/\($0.id)").path }
        let spec = LaunchSpec(version: version, playerName: account.username, playerUuid: account.uuid,
                              accessToken: account.accessToken, userType: account.userType, xuid: account.xuid,
                              gameDir: gameDir.path, assetsRoot: paths.assets.path, librariesDir: paths.libraries.path,
                              nativesDir: natives.path, classpath: classpath, width: width, height: height,
                              memoryMb: memoryMb, loggingConfigPath: logPath)
        return LaunchPlan(args: LaunchArgs.build(spec), gameDir: gameDir, nativesDir: natives, classpath: classpath,
                          javaMajorVersion: version.javaMajorVersion)
    }
}

/// The few account fields a launch needs (kept separate from the app's `Account` so the game logic stays UI-free).
struct AccountInfo {
    var username: String
    var uuid: String
    var accessToken: String
    var userType: String
    var xuid: String
}
