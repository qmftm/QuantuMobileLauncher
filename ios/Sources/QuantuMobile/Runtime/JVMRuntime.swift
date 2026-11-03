import Foundation

/// Where the Java Virtual Machine that runs the game plugs in.
///
/// This is the hard part of an iPhone version and it is NOT done: iOS needs a JVM built for iOS (as in PojavLauncher's iOS port),
/// JIT enabled for the process (debugger / AltStore / TrollStore), and a Metal-backed OpenGL layer. Until that is integrated,
/// `launch` only reports what is missing.
protocol JVMRuntime {
    var isAvailable: Bool { get }
    func launch(version: VersionEntry, account: Account) async throws
}

struct MissingJVMRuntime: JVMRuntime {
    var isAvailable: Bool { false }

    func launch(version: VersionEntry, account: Account) async throws {
        throw AuthError(message: "The Java runtime for iOS is not integrated yet (see ios/README.md).")
    }
}
