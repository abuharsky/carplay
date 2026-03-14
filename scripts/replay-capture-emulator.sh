#!/bin/sh
set -eu

CAPTURE_SRC="${1:-}"
CAPTURE_DST="${2:-/data/local/tmp/test-capture.bin}"
PKG="com.alexander.carplay.debug"
ACTIVITY="${PKG}/com.alexander.carplay.presentation.ui.CarPlayActivity"

if [ -z "$CAPTURE_SRC" ]; then
  echo "usage: $0 /absolute/path/to/test-capture.bin [/data/local/tmp/test-capture.bin]" >&2
  exit 1
fi

./gradlew installDebug
adb push "$CAPTURE_SRC" "$CAPTURE_DST"
adb shell am start \
  -n "$ACTIVITY" \
  --es replay_capture_path "$CAPTURE_DST"
