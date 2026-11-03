#!/usr/bin/env python3
"""Source changes QuantuMobile makes to Amethyst-iOS so Minecraft 26.x (SDL3 + LWJGL 3.4) can start.

Older versions keep Amethyst's own path untouched (LWJGL 3.3 + its GLFW). For a version whose libraries include
org.lwjgl:lwjgl-sdl, the launcher:
  - downloads and uses the game's own LWJGL 3.4 jars (only their desktop natives are skipped),
  - leaves Amethyst's lwjgl.jar (LWJGL 3.3 + GLFW) off the classpath,
  - points LWJGL at <app>/lwjgl34 (LWJGL 3.4 natives and the SDL3 stand-in from ios/sdl3shim) and at Amethyst's
    OpenAL, FreeType, shaderc and SPIRV-Cross,
  - renders through MobileGlues and passes --graphicsBackend opengl.
Plus a MobileGlues fix the Android build needed too: its uniform clean-up treated the "uniform" inside identifiers like
"_uniform" as a declaration and cut shader code apart.

usage: patch_amethyst.py <amethyst checkout>
"""
import os
import sys

ROOT = sys.argv[1]


def patch(rel, old, new, count=1):
    path = os.path.join(ROOT, rel)
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if text.count(old) != count:
        sys.exit("patch_amethyst: %s: expected %d match(es), found %d for:\n%s" % (rel, count, text.count(old), old))
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new))
    print("patched", rel)


# --- which libraries get downloaded ---
patch("Natives/MinecraftResourceUtils.m",
      """    // Exclude some libraries
    for (NSMutableDictionary *library in json[@"libraries"]) {""",
      """    // QuantuMobile: Minecraft 26.x (SDL3 + LWJGL 3.4) needs its own LWJGL jars; only their desktop natives are skipped
    BOOL qmSdl = NO;
    for (NSDictionary *l in json[@"libraries"]) {
        if ([l[@"name"] isKindOfClass:NSString.class] && [l[@"name"] containsString:@":lwjgl-sdl:"]) qmSdl = YES;
    }
    // Exclude some libraries
    for (NSMutableDictionary *library in json[@"libraries"]) {""")
patch("Natives/MinecraftResourceUtils.m",
      """            [library[@"name"] hasPrefix:@"org.lwjgl"]
        );""",
      """            ([library[@"name"] hasPrefix:@"org.lwjgl"] &&
             !(qmSdl && [library[@"name"] componentsSeparatedByString:@":"].count < 4))
        );""")

# --- JVM arguments and classpath ---
patch("Natives/JavaLauncher.m",
      """    // Preset OpenGL libname""",
      """    // QuantuMobile: Minecraft 26.x opens its window through SDL3 (LWJGL 3.4). The SDL3 stand-in in <app>/lwjgl34 drives
    // Amethyst's GL bridge and input queue; LWJGL 3.4's natives sit next to it, apart from Amethyst's LWJGL 3.3.
    BOOL qmSdl = NO;
    if ([launchTarget isKindOfClass:NSDictionary.class]) {
        for (NSDictionary *l in launchTarget[@"libraries"]) {
            if ([l isKindOfClass:NSDictionary.class] && [l[@"name"] isKindOfClass:NSString.class] &&
                [l[@"name"] containsString:@":lwjgl-sdl:"]) qmSdl = YES;
        }
    }
    if (qmSdl) {
        NSString *qmApp = NSBundle.mainBundle.bundlePath;
        NSLog(@"[JavaLauncher] QuantuMobile: SDL3 game, using LWJGL 3.4 from %@/lwjgl34", qmApp);
        setenv("RENDERER", RENDERER_NAME_MOBILEGLUES, 1);
        setenv("AME_SKIP_JNI_GLFW", "1", 1);
        margv[++margc] = "-Dqm.sdl=true";
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.librarypath=%@/lwjgl34", qmApp].UTF8String;
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.sdl.libname=%@/lwjgl34/libSDL3.dylib", qmApp].UTF8String;
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.freetype.libname=%@/Frameworks/libfreetype.dylib", qmApp].UTF8String;
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.openal.libname=%@/Frameworks/libopenal.dylib", qmApp].UTF8String;
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.shaderc.libname=%@/Frameworks/libshaderc.dylib", qmApp].UTF8String;
        margv[++margc] = [NSString stringWithFormat:@"-Dorg.lwjgl.spvc.libname=%@/Frameworks/libspirv-cross-c-shared.0.dylib", qmApp].UTF8String;
    }

    // Preset OpenGL libname""")
patch("Natives/JavaLauncher.m",
      """    NSString *classpath = [NSString stringWithFormat:@"%@/*", librariesPath];""",
      """    NSString *classpath = [NSString stringWithFormat:@"%@/*", librariesPath];
    if (qmSdl) {
        // Without lwjgl.jar (LWJGL 3.3 + Amethyst's GLFW): the game brings LWJGL 3.4 itself.
        NSMutableArray *qmJars = [NSMutableArray new];
        for (NSString *f in [fm contentsOfDirectoryAtPath:librariesPath error:nil]) {
            if ([f hasSuffix:@".jar"] && ![f isEqualToString:@"lwjgl.jar"]) {
                [qmJars addObject:[librariesPath stringByAppendingPathComponent:f]];
            }
        }
        classpath = [qmJars componentsJoinedByString:@":"];
    }""")

# --- the Java side of the launcher ---
patch("JavaApp/src/launcher/net/kdt/pojavlaunch/Tools.java",
      """    public static void preProcessLibraries(DependentLibrary[] libraries) {""",
      """    public static void preProcessLibraries(DependentLibrary[] libraries) {
        // QuantuMobile: Minecraft 26.x (SDL3 + LWJGL 3.4) keeps its own LWJGL jars, without their desktop natives
        boolean qmSdl = false;
        for (DependentLibrary l : libraries) {
            if (l.name != null && l.name.contains(":lwjgl-sdl:")) qmSdl = true;
        }""")
patch("JavaApp/src/launcher/net/kdt/pojavlaunch/Tools.java",
      """                libItem.name.startsWith("org.lwjgl") ||""",
      """                (libItem.name.startsWith("org.lwjgl") && !(qmSdl && libItem.name.split(":").length < 4)) ||""")
patch("JavaApp/src/launcher/net/kdt/pojavlaunch/Tools.java",
      """        String[] launchArgs = getMinecraftArgs(profile, versionInfo);""",
      """        String[] launchArgs = getMinecraftArgs(profile, versionInfo);
        if (System.getProperty("qm.sdl") != null) {
            // 26.x prefers Vulkan; on iPhone it runs on OpenGL through MobileGlues
            launchArgs = java.util.Arrays.copyOf(launchArgs, launchArgs.length + 2);
            launchArgs[launchArgs.length - 2] = "--graphicsBackend";
            launchArgs[launchArgs.length - 1] = "opengl";
        }""")

# --- MobileGlues: only a whole word "uniform" starts a uniform declaration ---
patch("Natives/external/MobileGlues/src/main/cpp/gl/glsl/glsl_for_es.cpp",
      """        if (glslCode.compare(scan_pos, 7, "uniform") == 0) {""",
      """        const auto qm_is_ident = [](char c) { return std::isalnum(static_cast<unsigned char>(c)) || c == '_'; };
        if (glslCode.compare(scan_pos, 7, "uniform") == 0 && (scan_pos == 0 || !qm_is_ident(glslCode[scan_pos - 1])) &&
            (scan_pos + 7 >= length || !qm_is_ident(glslCode[scan_pos + 7]))) {""")
