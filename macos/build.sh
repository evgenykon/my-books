#!/usr/bin/env bash
# Builds macos/dist/MyBooks.app from Sources/MyBooks using the shared Kotlin framework.
# Requires Shared.framework (produced by the macOS CI workflow) in this directory.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d "Shared.framework" ]; then
  echo "Shared.framework not found."
  echo "Download it from the 'macOS' workflow artifacts (shared-framework) and place it here."
  exit 1
fi

mkdir -p .build
SRC=()
while IFS= read -r f; do SRC+=("$f"); done < <(find Sources/MyBooks -name '*.swift' | sort)
echo "Sources: ${#SRC[@]} files"

build_arch() {
  local arch="$1"
  local out="$2"
  swiftc -O -swift-version 5 -parse-as-library \
    -target "${arch}-apple-macos14.0" \
    -F . -framework Shared \
    -o "$out" "${SRC[@]}"
}

build_arch arm64 .build/MyBooks-arm64

if build_arch x86_64 .build/MyBooks-x64; then
  lipo -create .build/MyBooks-arm64 .build/MyBooks-x64 -output .build/MyBooks
else
  echo "x86_64 slice failed, building arm64 only"
  cp .build/MyBooks-arm64 .build/MyBooks
fi

APP="dist/MyBooks.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/MyBooks "$APP/Contents/MacOS/MyBooks"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || echo "codesign skipped"

echo "Built $APP"

if [ "${2:-}" = "install" ] || [ "${1:-}" = "install" ]; then
  pkill -x MyBooks 2>/dev/null || true
  rm -rf "/Applications/MyBooks.app"
  ditto "$APP" "/Applications/MyBooks.app"
  xattr -dr com.apple.quarantine "/Applications/MyBooks.app" 2>/dev/null || true
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "/Applications/MyBooks.app"
  echo "Installed /Applications/MyBooks.app"
fi
