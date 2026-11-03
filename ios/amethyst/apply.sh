#!/usr/bin/env bash
# Turns a checkout of Amethyst-iOS (GPL-3.0, see ../UPSTREAM for the pinned commit) into QuantuMobile for iPhone:
# app name, bundle id and icons. Everything else is upstream code, unchanged. Runs on macOS (plutil, sips).
# Usage: apply.sh <amethyst-checkout>
set -euo pipefail
SRC="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
BRAND="$HERE/branding"
BUNDLE_ID="com.quantumobile.launcher.ios"
NAME="QuantuMobile"

plist="$SRC/Natives/Info.plist"
plutil -replace CFBundleDisplayName -string "$NAME" "$plist"
plutil -replace CFBundleIdentifier -string "$BUNDLE_ID" "$plist"
sed -i '' "s/org\.angelauramc\.amethyst\.urlscheme/$BUNDLE_ID.urlscheme/" "$plist"
for f in "$SRC"/entitlements.*.xml; do sed -i '' "s/org\.angelauramc\.amethyst/$BUNDLE_ID/g" "$f"; done

# Home Screen icon (asset catalog, compiled by `make assets`) and the pre-rendered fallbacks in resources/.
xc="$SRC/Natives/Assets.xcassets"
cp "$BRAND/icon-1024.png" "$xc/AppIcon-Light.appiconset/1024x1024.png"
cp "$BRAND/icon-1024-transparent.png" "$xc/AppIcon-Light.appiconset/1024x1024-Transparent.png"
cp "$BRAND/icon-1024-mono.png" "$xc/AppIcon-Light.appiconset/1024x1024-Monochrome-White.png"
cp "$BRAND/icon-1024.png" "$xc/AppIcon-Dark.appiconset/AppIcon-Dark_1024x1024.png"
cp "$BRAND/icon-1024-transparent.png" "$xc/AppLogo-Vector.imageset/1024x1024-Transparent.png"
res="$SRC/Natives/resources"
for n in AppIcon AppIcon-Light AppIcon-Dark; do
  sips -s format png -z 120 120 "$BRAND/icon-1024.png" --out "$res/${n}60x60@2x.png" >/dev/null
  sips -s format png -z 152 152 "$BRAND/icon-1024.png" --out "$res/${n}76x76@2x~ipad.png" >/dev/null
done

# Visible app name in the English and Korean UI text (the About line keeps the upstream name for attribution).
for lang in en ko; do
  f="$res/$lang.lproj/Localizable.strings"
  [ -f "$f" ] && sed -i '' -e 's/Angel Aura Amethyst/QuantuMobile/g' -e 's/Amethyst/QuantuMobile/g' "$f"
done
echo "QuantuMobile branding applied to $SRC"
