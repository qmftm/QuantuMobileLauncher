// swift-tools-version:5.9
// Lets CI run the launcher's game logic tests with `swift test` (the app itself is built from project.yml).
import PackageDescription

let package = Package(
    name: "QuantuGame",
    platforms: [.macOS(.v13), .iOS(.v16)],
    targets: [
        .target(name: "QuantuGame", path: "Sources/QuantuMobile/Game"),
        .testTarget(name: "GameTests", dependencies: ["QuantuGame"], path: "Tests/GameTests"),
    ]
)
