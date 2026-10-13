#!/bin/bash
# Builds app/src/main/assets/qm-debug-agent.jar (Java 8 bytecode) from src/QmDebugAgent.java.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
javac --release 8 -d "$OUT" "$HERE/src/QmDebugAgent.java"
printf 'Premain-Class: QmDebugAgent\n' > "$OUT/manifest.txt"
jar cfm "$HERE/../../app/src/main/assets/qm-debug-agent.jar" "$OUT/manifest.txt" -C "$OUT" QmDebugAgent.class
