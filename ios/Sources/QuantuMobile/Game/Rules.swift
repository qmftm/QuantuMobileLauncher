import Foundation

typealias JSON = [String: Any]

/// Environment used to evaluate the `rules` blocks in version JSON files (port of the Android launcher's Rules.kt).
struct RuleEnv {
    /// Mojang OS name. The iOS JVM reports itself like macOS (`osx`); not yet verified against a real iOS runtime.
    var osName = "osx"
    var arch = "arm64"
    var features: [String: Bool] = [:]
}

enum Rules {
    /// Mojang semantics: no rules → allowed; otherwise the last matching rule wins, default deny.
    static func isAllowed(_ rules: [Any]?, env: RuleEnv) -> Bool {
        guard let rules, !rules.isEmpty else { return true }
        var allowed = false
        for element in rules {
            guard let rule = element as? JSON else { continue }
            if matches(rule, env: env) { allowed = (rule["action"] as? String) == "allow" }
        }
        return allowed
    }

    private static func matches(_ rule: JSON, env: RuleEnv) -> Bool {
        if let os = rule["os"] as? JSON {
            if let name = os["name"] as? String, name != env.osName { return false }
            if let arch = os["arch"] as? String, arch != env.arch { return false }
        }
        if let features = rule["features"] as? JSON {
            for (key, value) in features {
                guard let expected = value as? Bool else { return false }
                if (env.features[key] ?? false) != expected { return false }
            }
        }
        return true
    }
}
