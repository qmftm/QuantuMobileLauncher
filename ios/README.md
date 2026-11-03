# QuantuMobile for iPhone (early skeleton)

The iPhone app lives here, separate from the Android launcher in `app/`. **It cannot run the game yet.** What exists:

- Sign-in: Microsoft accounts (same Xbox/XSTS/Minecraft-services chain as Android) and offline accounts (same UUID rule).
- Version list from Mojang's manifest, an account list, and a Play screen.
- `Runtime/JVMRuntime.swift`: the plug point for the Java runtime. The default `MissingJVMRuntime` only says it is missing.

## What is still missing (the hard part)

1. A **JVM built for iOS** (PojavLauncher's iOS port is the reference).
2. **JIT** for the app process. Normal apps cannot get it; sideloading via AltStore / TrollStore or a debugger can.
3. A **Metal-backed OpenGL layer** (the Android build uses MobileGlues for GLES; iOS needs something else, e.g. ANGLE-on-Metal).
4. Downloading the game (libraries, assets, natives) and the launch arguments (the Android logic in `core/` is the model).
5. Touch controls and input.

The App Store will not accept this; it is for sideloading.

## Building

There is no Xcode here: CI builds it. Run the **iOS build** workflow (Actions tab); it uses XcodeGen (`project.yml`) on a macOS runner and
uploads `QuantuMobile-unsigned.ipa`. Sign and install it with your own tool (AltStore, Sideloadly, TrollStore).

On a Mac: `brew install xcodegen && cd ios && xcodegen generate && open QuantuMobile.xcodeproj`.
