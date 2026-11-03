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
