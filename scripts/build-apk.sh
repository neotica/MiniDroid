#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android-app"
IMG="$ROOT/images"
ASSETS="$APP/app/src/main/assets/images"
JNILIBS="$APP/app/src/main/jniLibs/arm64-v8a"

if [ ! -f "$ROOT/build-android/out/arm64-v8a/libqemu.so" ]; then
    echo "Building Android arm64-v8a QEMU binary..."
    "$ROOT/scripts/build-android-deps.sh"
    "$ROOT/scripts/build-android-qemu.sh"
fi

mkdir -p "$JNILIBS" "$ASSETS"
cp "$ROOT/build-android/out/arm64-v8a/libqemu.so" "$JNILIBS/libqemu.so"

if [ ! -f "$ASSETS/system.img.z" ]; then
    echo "Packing Android 1.6 images into APK assets..."
    cp "$IMG/kernel-qemu" "$IMG/ramdisk.img" "$ASSETS/"
    gzip -6 -c "$IMG/system.img" > "$ASSETS/system.img.z"
    gzip -6 -c "$IMG/userdata.img" > "$ASSETS/userdata.img.z"
    gzip -9 -c "$IMG/sdcard.img" > "$ASSETS/sdcard.img.z"
fi

cd "$APP"
if [ -x "./gradlew" ]; then
    ./gradlew assembleDebug
else
    gradle assembleDebug
fi

echo "=== MiniDroid APK ready ==="
ls -lh "$APP/app/build/outputs/apk/debug/app-debug.apk"
