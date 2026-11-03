# QuantuMobile for iPhone

The iPhone app is **[Amethyst-iOS](https://github.com/AngelAuraMC/Amethyst-iOS)** (GPL-3.0) with QuantuMobile's name, bundle id and icons.
This folder only holds what we change; the upstream source is fetched at the commit pinned in [`amethyst/UPSTREAM`](amethyst/UPSTREAM).

- `amethyst/apply.sh` — applies the branding to an upstream checkout (macOS: `plutil`, `sips`).
- `amethyst/branding/` — icons.
- The **iOS build** workflow (`.github/workflows/ios.yml`) fetches upstream, applies the branding, builds with upstream's Makefile and
  uploads `QuantuMobile-ios.ipa` (SideStore/AltStore) and `QuantuMobile-ios-trollstore.tipa` (TrollStore).

## License

Because it is built from Amethyst-iOS, the iPhone app is licensed under the **GNU GPL v3.0**. Its complete corresponding source is
the pinned upstream commit plus this folder. Credits for Amethyst, PojavLauncher and Boardwalk stay in the app's About screen.

## Installing (iPhone 15 and other iOS 17+ devices)

1. Install **SideStore** (or AltStore) and sideload `QuantuMobile-ios.ipa`.
2. Enable **JIT** with **StikDebug** each time you start the game (on iOS 26 the app hands StikDebug its JIT script).
3. TrollStore only works on older iOS versions; there use the `.tipa`.

## Minecraft 26.x

26.x opens its window through SDL3 and needs LWJGL 3.4, which Amethyst does not have. QuantuMobile adds:

- `sdl3shim/` — a small SDL3 stand-in (`libSDL3.dylib`) that implements the SDL functions Minecraft uses on top of
  Amethyst's GL bridge and input queue (`backend_amethyst.m`). `backend_glx.c` runs the same code on a Linux desktop,
  which is how it was checked against the real game (title screen, world, typing, mouse look).
- `natives/build-ios-natives.sh` — builds LWJGL 3.4.3 (core, opengl, stb, vma) for iOS and the shim; they go into
  `<app>/lwjgl34`.
- `amethyst/patch_amethyst.py` — for versions with `lwjgl-sdl`: downloads the game's own LWJGL jars, leaves Amethyst's
  LWJGL 3.3 off the classpath, points LWJGL at `lwjgl34`, uses MobileGlues and `--graphicsBackend opengl`.
  Also fixes a MobileGlues shader bug (`_uniform` identifiers) that broke 26.x terrain shaders on Android too.
