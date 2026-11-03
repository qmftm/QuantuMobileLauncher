import Foundation

/// Where the Java Virtual Machine that runs the game plugs in.
///
/// This is the hard part of an iPhone version and it is NOT done: iOS needs a JVM built for iOS (as in PojavLauncher's iOS port),
/// JIT enabled for the process (debugger / AltStore / TrollStore), and a Metal-backed OpenGL layer. Until that is integrated,
/// `launch` only reports what is missing. The game files and the full `java` argument list are already prepared by `LaunchPlanner`.
protocol JVMRuntime {
    var isAvailable: Bool { get }
    func launch(plan: LaunchPlan) async throws
}

struct MissingJVMRuntime: JVMRuntime {
    var isAvailable: Bool { false }

    func launch(plan: LaunchPlan) async throws {
        throw AuthError(message: "The game is downloaded and ready (Java \(plan.javaMajorVersion), \(plan.classpath.count) classpath entries), " +
                        "but the Java runtime for iOS is not integrated yet (see ios/README.md).")
    }
}
