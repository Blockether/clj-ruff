#!/usr/bin/env bash
# Build the ruff-c cdylib (native/ruff-c) for ONE platform and stage it at
# resources/prebuilds/<platform>/<lib> — the layout the native-jar build and the
# runtime FFM resolver expect.
#
# Usage:
#   scripts/build-natives.sh <platform> [<rust-target-triple>]
#   platform ∈ { linux-x64 linux-arm64 darwin-arm64 darwin-x64 windows-x64 }
#
# With no triple it builds for the host (cargo default target). The cdylib wraps
# ruff_python_formatter pinned in native/ruff-c/Cargo.toml (the ruff release tag).
set -euo pipefail

PLATFORM="${1:?usage: build-natives.sh <platform> [<triple>]}"
TRIPLE="${2:-}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

case "$PLATFORM" in
  linux-x64|linux-arm64)   LIB="libruff_c.so" ;;
  darwin-arm64|darwin-x64) LIB="libruff_c.dylib" ;;
  windows-x64)             LIB="ruff_c.dll" ;;
  *) echo "unknown platform: $PLATFORM" >&2; exit 1 ;;
esac

cd "$HERE/native/ruff-c"
if [ -n "$TRIPLE" ]; then
  cargo build --release --target "$TRIPLE"
  OUT="target/$TRIPLE/release/$LIB"
else
  cargo build --release
  OUT="target/release/$LIB"
fi
[ -f "$OUT" ] || { echo "built lib not found: $OUT" >&2; exit 1; }

DEST="$HERE/resources/prebuilds/$PLATFORM"
mkdir -p "$DEST"
cp "$OUT" "$DEST/$LIB"
echo "staged $DEST/$LIB"
