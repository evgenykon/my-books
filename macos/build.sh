#!/usr/bin/env bash
# Builds MyBooks.app into macos/dist. Requires Shared.xcframework in macos/.
set -euo pipefail
cd "$(dirname "$0")"

CONFIG="${1:-release}"

if [ ! -d "Shared.xcframework" ]; then
  echo "Shared.xcframework not found."
  echo "Build it with: gradle :shared:assembleSharedReleaseXCFramework, then copy the result here."
  exit 1
fi

swift build -c "$CONFIG" --product MyBooks
BIN_PATH="$(swift build -c "$CONFIG" --show-bin-path)/MyBooks"

APP="dist/MyBooks.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_PATH" "$APP/Contents/MacOS/MyBooks"
cp Resources/Info.plist "$APP/Contents/Info.plist"
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "Built $APP"
