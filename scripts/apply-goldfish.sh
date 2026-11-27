#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QEMU_DIR="${1:-$ROOT/qemu-11.1.3}"

if [ ! -d "$QEMU_DIR/hw/arm" ]; then
    echo "Usage: $0 [path/to/qemu-11.1.3]"
    exit 1
fi

mkdir -p "$QEMU_DIR/hw/arm/goldfish"
cp -v "$ROOT"/goldfish/hw/arm/goldfish/* "$QEMU_DIR/hw/arm/goldfish/"

if ! grep -q "CONFIG_ANDROID_GOLDFISH" "$QEMU_DIR/hw/arm/Kconfig"; then
    patch -d "$QEMU_DIR" -p1 < "$ROOT/goldfish/qemu-11.1.3-wire-goldfish.patch"
else
    echo "Goldfish already wired in Kconfig/meson.build"
fi
