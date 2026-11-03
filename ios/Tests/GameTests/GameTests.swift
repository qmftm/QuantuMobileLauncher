import XCTest
@testable import QuantuGame

final class GameTests: XCTestCase {
    private func obj(_ s: String) -> JSON { try! JSONSerialization.jsonObject(with: Data(s.utf8)) as! JSON }

    func testRulesLastMatchWins() {
        let env = RuleEnv(osName: "osx", arch: "arm64")
        let rules = obj(#"{"r":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]}"#)["r"] as! [Any]
        XCTAssertFalse(Rules.isAllowed(rules, env: env))
        XCTAssertTrue(Rules.isAllowed(nil, env: env))
        let onlyLinux = obj(#"{"r":[{"action":"allow","os":{"name":"linux"}}]}"#)["r"] as! [Any]
        XCTAssertFalse(Rules.isAllowed(onlyLinux, env: env))
    }

    func testFeatureRules() {
        let rules = obj(#"{"r":[{"action":"allow","features":{"has_custom_resolution":true}}]}"#)["r"] as! [Any]
        XCTAssertTrue(Rules.isAllowed(rules, env: RuleEnv(features: ["has_custom_resolution": true])))
        XCTAssertFalse(Rules.isAllowed(rules, env: RuleEnv()))
    }

    func testMavenCoords() throws {
        let c = try MavenCoords.parse("org.lwjgl:lwjgl:3.3.3:natives-macos-arm64")
        XCTAssertEqual(c.path, "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar")
        XCTAssertEqual(c.key, "org.lwjgl:lwjgl:natives-macos-arm64")
        XCTAssertEqual(try MavenCoords.parse("a.b:c:1.0@zip").path, "a/b/c/1.0/c-1.0.zip")
        XCTAssertThrowsError(try MavenCoords.parse("bad"))
    }

    func testSubstituteKeepsUnknown() {
        XCTAssertEqual(LaunchArgs.substitute("--n ${a} ${zz} $x ${", ["a": "1"]), "--n 1 ${zz} $x ${")
    }

    func testMergeFabricOntoVanilla() throws {
        let parent = try VersionInfo.parse(obj(#"""
        {"id":"1.21.1","mainClass":"net.minecraft.client.main.Main","downloads":{"client":{"url":"u","sha1":"s","size":1}},
         "libraries":[{"name":"a:b:1"}],"arguments":{"game":["--x"],"jvm":["-A"]}}
        """#))
        let child = obj(#"""
        {"id":"fabric-1.21.1","inheritsFrom":"1.21.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
         "libraries":[{"name":"c:d:2"}],"arguments":{"game":[],"jvm":["-B"]}}
        """#)
        let merged = try VersionInfo.merge(child: child, parent: parent)
        XCTAssertEqual(merged.id, "fabric-1.21.1")
        XCTAssertEqual(merged.jarId, "1.21.1")
        XCTAssertEqual(merged.mainClass, "net.fabricmc.loader.impl.launch.knot.KnotClient")
        XCTAssertEqual(try merged.libraries().map { $0.name }, ["c:d:2", "a:b:1"])
        XCTAssertEqual(merged.jvmArguments as? [String], ["-A", "-B"])
    }

    func testClasspathDropsNativesAndDuplicates() throws {
        let v = try VersionInfo.parse(obj(#"""
        {"id":"v","mainClass":"M","libraries":[
          {"name":"x:y:1"},{"name":"x:y:2"},{"name":"n:n:1:natives-macos"},
          {"name":"o:o:1","rules":[{"action":"allow","os":{"name":"windows"}}]}]}
        """#))
        XCTAssertEqual(try GameInstaller.classpathLibraries(v, env: RuleEnv()).map { $0.name }, ["x:y:1"])
    }

    func testBuildModernArgs() throws {
        let v = try VersionInfo.parse(obj(#"""
        {"id":"1.21.1","type":"release","mainClass":"Main","assets":"17",
         "arguments":{"game":["--username","${auth_player_name}",
            {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}"]},
            {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
         "jvm":["-Djava.library.path=${natives_directory}","-cp","${classpath}"]}}
        """#))
        let spec = LaunchSpec(version: v, playerName: "Steve", playerUuid: "u", accessToken: "t", userType: "msa", xuid: "",
                              gameDir: "/g", assetsRoot: "/a", librariesDir: "/l", nativesDir: "/n", classpath: ["/c1", "/c2"],
                              width: 800, height: 400, memoryMb: 1024)
        let args = LaunchArgs.build(spec)
        XCTAssertEqual(args, ["-Xms512M", "-Xmx1024M", "-Dlog4j2.formatMsgNoLookups=true", "-Djava.library.path=/n", "-cp", "/c1:/c2",
                              "Main", "--username", "Steve", "--width", "800"])
    }

    func testBuildLegacyArgs() throws {
        let v = try VersionInfo.parse(obj(#"{"id":"1.6","mainClass":"M","minecraftArguments":"--username ${auth_player_name}"}"#))
        let spec = LaunchSpec(version: v, playerName: "S", playerUuid: "u", accessToken: "t", userType: "legacy", xuid: "", gameDir: "/g",
                              assetsRoot: "/a", librariesDir: "/l", nativesDir: "/n", classpath: ["/c"], width: 1, height: 2, memoryMb: 256)
        let args = LaunchArgs.build(spec)
        XCTAssertEqual(Array(args.suffix(7)), ["M", "--username", "S", "--width", "1", "--height", "2"])
        XCTAssertTrue(args.contains("-cp"))
    }

    func testAssetIndexObjectPath() throws {
        let idx = try AssetIndex.parse(Data(#"{"objects":{"a/b.png":{"hash":"abcdef","size":3}},"virtual":true}"#.utf8))
        XCTAssertEqual(idx.objects.first?.objectPath, "ab/abcdef")
        XCTAssertTrue(idx.isVirtual)
    }
}
