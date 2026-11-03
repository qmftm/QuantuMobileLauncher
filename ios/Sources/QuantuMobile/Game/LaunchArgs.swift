import Foundation

/// Everything needed to turn a resolved version into a Java command line.
struct LaunchSpec {
    var version: VersionInfo
    var playerName: String
    var playerUuid: String
    var accessToken: String
    var userType: String
    var xuid: String
    var gameDir: String
    var assetsRoot: String
    var librariesDir: String
    var nativesDir: String
    var classpath: [String]
    var width: Int
    var height: Int
    var memoryMb: Int
    var launcherName = "QuantuMobile"
    var launcherVersion = "0.1.0"
    var extraJvmArgs: [String] = []
    var loggingConfigPath: String?
}

enum LaunchArgs {
    /// Replaces `${name}` placeholders; unknown names are left as they are.
    static func substitute(_ value: String, _ vars: [String: String]) -> String {
        guard value.contains("$") else { return value }
        let chars = Array(value)
        var out = ""
        var i = 0
        while i < chars.count {
            if chars[i] == "$", i + 1 < chars.count, chars[i + 1] == "{",
               let end = chars[(i + 2)...].firstIndex(of: "}"), end > i + 2 {
                let name = String(chars[(i + 2)..<end])
                out += vars[name] ?? String(chars[i...end])
                i = end + 1
                continue
            }
            out.append(chars[i])
            i += 1
        }
        return out
    }

    /// Expands a modern `arguments.game`/`arguments.jvm` array, honouring per-entry rules.
    static func expand(_ args: [Any]?, env: RuleEnv, vars: [String: String]) -> [String] {
        var out: [String] = []
        for element in args ?? [] {
            if let s = element as? String {
                out.append(substitute(s, vars))
            } else if let o = element as? JSON, Rules.isAllowed(o["rules"] as? [Any], env: env) {
                if let s = o["value"] as? String { out.append(substitute(s, vars)) }
                else if let list = o["value"] as? [Any] { for v in list { if let s = v as? String { out.append(substitute(s, vars)) } } }
            }
        }
        return out
    }

    static func variables(_ spec: LaunchSpec) -> [String: String] {
        let v = spec.version
        let assetsDir = (v.assets == "legacy" || v.assets == "pre-1.6") ? "\(spec.assetsRoot)/virtual/\(v.assets)" : spec.assetsRoot
        return [
            "auth_player_name": spec.playerName,
            "version_name": v.id,
            "game_directory": spec.gameDir,
            "assets_root": spec.assetsRoot,
            "game_assets": assetsDir,
            "assets_index_name": v.assets,
            "auth_uuid": spec.playerUuid,
            "auth_access_token": spec.accessToken,
            "auth_session": "token:\(spec.accessToken):\(spec.playerUuid)",
            "auth_xuid": spec.xuid,
            "clientid": "",
            "user_type": spec.userType,
            "user_properties": "{}",
            "version_type": v.type,
            "natives_directory": spec.nativesDir,
            "launcher_name": spec.launcherName,
            "launcher_version": spec.launcherVersion,
            "classpath": spec.classpath.joined(separator: ":"),
            "classpath_separator": ":",
            "library_directory": spec.librariesDir,
            "resolution_width": String(spec.width),
            "resolution_height": String(spec.height),
        ]
    }

    /// Full argument list after the `java` executable: JVM options, main class, game options.
    static func build(_ spec: LaunchSpec) -> [String] {
        let v = spec.version
        let vars = variables(spec)
        let env = RuleEnv(features: ["has_custom_resolution": true])

        var jvm: [String] = ["-Xms\(min(512, spec.memoryMb))M", "-Xmx\(spec.memoryMb)M"]
        jvm += spec.extraJvmArgs
        if let path = spec.loggingConfigPath, let log = v.logging { jvm.append(substitute(log.argument, ["path": path])) }
        jvm.append("-Dlog4j2.formatMsgNoLookups=true")

        if let jvmArgs = v.jvmArguments {
            jvm += expand(jvmArgs, env: env, vars: vars)
        } else {
            jvm += ["-Djava.library.path=\(spec.nativesDir)", "-cp", vars["classpath"] ?? ""]
        }

        let game: [String]
        if let gameArgs = v.gameArguments {
            game = expand(gameArgs, env: env, vars: vars)
        } else {
            game = (v.legacyArguments ?? "").split(separator: " ").map { substitute(String($0), vars) }
                + ["--width", String(spec.width), "--height", String(spec.height)]
        }
        return jvm + [v.mainClass] + game
    }
}
