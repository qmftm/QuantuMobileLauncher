#!/bin/bash
# Builds app/src/main/assets/qm-debug-agent.jar (Java 25 bytecode: it uses java.lang.classfile) from src/*.java.
# Needs a JDK 25 javac: set JAVA25_HOME, or have one on PATH.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JBIN="${JAVA25_HOME:+$JAVA25_HOME/bin/}"
OUT="$(mktemp -d)"; MF="$(mktemp)"; trap 'rm -rf "$OUT" "$MF"' EXIT
"${JBIN}javac" --release 25 -d "$OUT" "$HERE"/src/*.java
printf 'Premain-Class: QmDebugAgent\n' > "$MF"
"${JBIN}jar" cfm "$HERE/../../app/src/main/assets/qm-debug-agent.jar" "$MF" -C "$OUT" .
