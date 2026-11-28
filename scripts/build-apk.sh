#!/usr/bin/env bash
set -euo pipefail

FLAVOR="${1:-donut}"
FLAVOR_CAP="$(printf '%s' "$FLAVOR" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android-app"
IMG="$ROOT/images"
ASSETS="$APP/app/src/$FLAVOR/assets/images"
KEYMAPS="$APP/app/src/main/assets/keymaps"
JNILIBS="$APP/app/src/main/jniLibs/arm64-v8a"

if [ ! -f "$ROOT/build-android/out/arm64-v8a/libqemu.so" ]; then
    echo "Building Android arm64-v8a QEMU binary..."
    "$ROOT/scripts/build-android-deps.sh"
    "$ROOT/scripts/build-android-qemu.sh"
fi

mkdir -p "$JNILIBS" "$ASSETS" "$KEYMAPS"
cp "$ROOT/build-android/out/arm64-v8a/libqemu.so" "$JNILIBS/libqemu.so"
if [ ! -f "$KEYMAPS/en-us" ] && [ -f "$ROOT/qemu-11.1.3/pc-bios/keymaps/en-us" ]; then
    cp "$ROOT/qemu-11.1.3/pc-bios/keymaps/en-us" "$KEYMAPS/en-us"
fi

if [ ! -f "$ASSETS/system.img.z" ]; then
    echo "Packing $FLAVOR guest images into APK assets..."
    cp "$IMG/kernel-qemu" "$IMG/ramdisk.img" "$ASSETS/"
    gzip -6 -c "$IMG/system.img" > "$ASSETS/system.img.z"
    gzip -6 -c "$IMG/userdata.img" > "$ASSETS/userdata.img.z"
fi

cd "$APP"
TASK="assemble${FLAVOR_CAP}Debug"
if [ -x "./gradlew" ]; then
    ./gradlew "$TASK"
else
    gradle "$TASK"
fi

echo "=== MiniDroid ($FLAVOR) APK ready ==="
ls -lh "$APP/app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
