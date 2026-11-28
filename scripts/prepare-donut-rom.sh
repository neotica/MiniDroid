#!/usr/bin/env bash
set -euo pipefail

# Fetches official Android 1.6 (Donut) SDK images + latest neotica/HoloMarket APK,
# injects HoloMarket into /system/app/HoloMarket.apk with Home screen pin, and packs
# the compressed images into android-app/app/src/donut/assets/images/.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG="$ROOT/images"
ASSETS="$ROOT/android-app/app/src/donut/assets/images"
HOLOMARKET_REPO="${HOLOMARKET_REPO:-neotica/HoloMarket}"
HOLOMARKET_TAG="${HOLOMARKET_TAG:-latest}"

mkdir -p "$IMG" "$ASSETS"

# 1. Fetch official Android 1.6 (android-1.6_r03) SDK emulator images
if [ ! -f "$IMG/system-stock.img" ] || [ ! -f "$IMG/kernel-qemu" ] || [ ! -f "$IMG/ramdisk.img" ] || [ ! -f "$IMG/userdata.img" ]; then
    if [ ! -f "$IMG/android-1.6_r03-linux.zip" ]; then
        echo "=== Downloading Android 1.6 (android-1.6_r03) SDK image ==="
        curl -fSL -o "$IMG/android-1.6_r03-linux.zip.tmp" \
            "https://dl.google.com/android/repository/android-1.6_r03-linux.zip"
        mv "$IMG/android-1.6_r03-linux.zip.tmp" "$IMG/android-1.6_r03-linux.zip"
    fi
    echo "=== Extracting stock Android 1.6 images ==="
    unzip -oj "$IMG/android-1.6_r03-linux.zip" "android-1.6_r03-linux/images/*" -d "$IMG"
    cp "$IMG/system.img" "$IMG/system-stock.img"
fi

# 2. Fetch HoloMarket APK from GitHub Releases (unless HOLOMARKET_APK is provided)
APK_PATH="${HOLOMARKET_APK:-$IMG/HoloMarket.apk}"
if [ -z "${HOLOMARKET_APK:-}" ]; then
    echo "=== Resolving HoloMarket APK from $HOLOMARKET_REPO ($HOLOMARKET_TAG) ==="
    if [ "$HOLOMARKET_TAG" = "latest" ]; then
        API_URL="https://api.github.com/repos/$HOLOMARKET_REPO/releases/latest"
    else
        API_URL="https://api.github.com/repos/$HOLOMARKET_REPO/releases/tags/$HOLOMARKET_TAG"
    fi

    AUTH_HEADER=()
    if [ -n "${GITHUB_TOKEN:-}" ]; then
        AUTH_HEADER=(-H "Authorization: Bearer $GITHUB_TOKEN")
    fi

    RELEASE_JSON="$(curl -fsSL "${AUTH_HEADER[@]}" -H "Accept: application/vnd.github+json" "$API_URL")"
    APK_URL="$(printf '%s' "$RELEASE_JSON" | python3 -c '
import json, sys
rel = json.load(sys.stdin)
assets = rel.get("assets", [])
apks = [a for a in assets if a.get("name", "").endswith(".apk")]
if not apks:
    sys.exit(1)
print(apks[0]["browser_download_url"])
')"
    APK_NAME="$(printf '%s' "$RELEASE_JSON" | python3 -c '
import json, sys
rel = json.load(sys.stdin)
apks = [a for a in rel.get("assets", []) if a.get("name", "").endswith(".apk")]
print(apks[0]["name"])
')"
    echo "Downloading $APK_NAME from $APK_URL ..."
    curl -fSL "${AUTH_HEADER[@]}" -o "$APK_PATH.tmp" "$APK_URL"
    mv "$APK_PATH.tmp" "$APK_PATH"
fi

# 3. Inject HoloMarket into /system/app/HoloMarket.apk + Home screen shortcut
echo "=== Baking HoloMarket into system.img ==="
python3 "$ROOT/scripts/inject-system-apk.py" \
    "$IMG/system-stock.img" \
    "$APK_PATH" \
    "$IMG/system.img" \
    HoloMarket.apk

# 4. Pack into donut flavor assets
echo "=== Packing Donut assets into $ASSETS ==="
cp "$IMG/kernel-qemu" "$IMG/ramdisk.img" "$ASSETS/"
gzip -6 -c "$IMG/system.img" > "$ASSETS/system.img.z"
gzip -6 -c "$IMG/userdata.img" > "$ASSETS/userdata.img.z"
ls -lh "$ASSETS"
