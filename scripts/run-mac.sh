#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QEMU="$ROOT/build-mac/qemu-system-arm"
IMG="$ROOT/images"

if [ ! -f "$IMG/userdata-work.img" ]; then
    cp "$IMG/userdata.img" "$IMG/userdata-work.img"
fi

DISPLAY_ARGS=(-display cocoa)
if [ "${HEADLESS:-0}" = "1" ]; then
    DISPLAY_ARGS=(-display none -vnc 127.0.0.1:0)
fi

exec "$QEMU" \
    -M "goldfish,system=$IMG/system.img,userdata=$IMG/userdata-work.img,cache=$IMG/cache-work.img,sdcard=$IMG/sdcard.img,width=320,height=480" \
    -m 128M \
    -kernel "$IMG/kernel-qemu" \
    -initrd "$IMG/ramdisk.img" \
    -append "qemu=1 console=ttyS0 android.checkjni=1 android.ndns=1" \
    -nic "user,model=smc91c111,hostfwd=tcp:127.0.0.1:5555-:5555" \
    -serial stdio \
    "${DISPLAY_ARGS[@]}" \
    "$@"
