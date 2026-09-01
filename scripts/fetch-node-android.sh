#!/usr/bin/env bash
#
# Stages the standalone Node.js executable for Android (arm64) into ./native/bin.
#
# We run stremio's server.js by spawning a self-contained Node executable the
# same way the server spawns ffmpeg/ffprobe. The Node runtime is therefore NOT a
# linkable/embedded libnode.so — it's a PIE executable (bin/node) that the app
# ProcessBuilder.exec()s. We place it at <repo>/native/bin/<abi>/libnode.so
# (a `.so` name so AGP reliably packages it into lib/<abi>/ and extracts it to
# nativeLibraryDir at install time) alongside the ffmpeg/ffprobe executables
# staged by scripts/build-ffmpeg-android.sh.
#
# The standalone Node binary is hand-provided (e.g. a release from
# nodejs.org/dist, or a `node-android-build` Actions release) and dropped as a
# tree like ./nodejs-vX.Y.Z-android-arm64/ at the repo root. This script just
# picks that up and stages the pieces the app expects. It does NOT download
# anything; the binary is too large / user-only to fetch on demand.
#
# Usage: ./scripts/fetch-node-android.sh [path/to/extracted-node-tree]
#   e.g. ./scripts/fetch-node-android.sh nodejs-v26.6.0-android-arm64

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/native"

# The extracted standalone Node tree to stage. Default to the newest
# nodejs-v*-android-* tree at the repo root, or an explicit path argument.
SRC_DIR="${1:-}"
if [ -z "$SRC_DIR" ]; then
  SRC_DIR="$(ls -d "$ROOT"/nodejs-v*-android-* 2>/dev/null | head -1 || true)"
fi
if [ -z "$SRC_DIR" ] || [ ! -d "$SRC_DIR" ]; then
  echo "ERROR: no standalone Node tree found. Pass one, e.g.: $0 nodejs-v26.6.0-android-arm64" >&2
  exit 1
fi
SRC_DIR="$(cd "$SRC_DIR" && pwd)"

NODE_BIN="$SRC_DIR/bin/node"
if [ ! -f "$NODE_BIN" ]; then
  echo "ERROR: $NODE_BIN not found." >&2
  exit 1
fi

echo "==> Staging Node from: $SRC_DIR"
echo "    binary: $(file "$NODE_BIN" | sed 's/^[^:]*: //')"

# Version tag from the embedded headers so the log is easy to sanity-check.
VERSION="$(grep -E '#define NODE_MAJOR_VERSION|#define NODE_MINOR_VERSION|#define NODE_PATCH_VERSION' "$SRC_DIR/include/node/node_version.h" | tr '\n' ' ' | sed -E 's/[^0-9. ]//g')"
echo "    version (from headers): $VERSION"

ABI="arm64-v8a"
echo "==> Copying $NODE_BIN -> $DEST_DIR/bin/$ABI/libnode.so"
mkdir -p "$DEST_DIR/bin/$ABI"
cp "$NODE_BIN" "$DEST_DIR/bin/$ABI/libnode.so"
chmod +x "$DEST_DIR/bin/$ABI/libnode.so"

echo "==> Replacing node headers (for any tooling that inspects them)"
rm -rf "$DEST_DIR/include"
cp -r "$SRC_DIR/include" "$DEST_DIR/include"
chmod -R u+w "$DEST_DIR/include"

echo "==> Done. Verify:"
file "$DEST_DIR/bin/$ABI/libnode.so"
grep -E "NODE_MAJOR_VERSION|NODE_MINOR_VERSION|NODE_PATCH_VERSION" "$DEST_DIR/include/node/node_version.h"
