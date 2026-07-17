#!/usr/bin/env bash
# Build, install, and re-select the Grid24 IME on the connected device.
# Reinstalls sometimes drop the active-IME selection — the `ime set` fixes that.
# First-ever install additionally needs a one-time:  adb shell ime enable "$IME_ID"
set -euo pipefail
cd "$(dirname "$0")/.."

IME_ID="io.github.wksaurey.grid24/.Grid24Ime"

./gradlew installDebug
adb shell ime enable "$IME_ID" >/dev/null 2>&1 || true
adb shell ime set "$IME_ID"
echo "Grid24 installed and active."
