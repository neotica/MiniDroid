#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="$ROOT/deps-android"
PREFIX="$DEPS/prefix"
BUILD_ROOT="$ROOT/build-android"
SRC="$BUILD_ROOT/qemu-11.1.3"
OUT="$BUILD_ROOT/build"
DIST="$BUILD_ROOT/out/arm64-v8a"

case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux)  HOST_TAG="linux-x86_64" ;;
    *)      echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac

if [ -d /opt/homebrew/bin ]; then
    export PATH="/opt/homebrew/bin:$PATH"
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-$HOME/Library/Android/sdk/ndk/27.1.12297006}}}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "Android NDK toolchain not found at: $TOOLCHAIN" >&2
    echo "Set ANDROID_NDK_HOME to an installed Android NDK (r27+)." >&2
    exit 1
fi
API=28

export PATH="$BUILD_ROOT/bin:$TOOLCHAIN/bin:$PATH"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"

PKG_CONFIG_BIN="${PKG_CONFIG:-$(command -v pkg-config)}"
PYTHON_BIN="${PYTHON:-$(command -v python3)}"
HOST_CC_BIN="${HOST_CC:-$(command -v cc)}"

export PKG_CONFIG="$PKG_CONFIG_BIN"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""

mkdir -p "$BUILD_ROOT" "$DIST"

# 0. Fetch QEMU 11.1.3 tarball if missing
if [ ! -f "$ROOT/qemu-11.1.3.tar.xz" ]; then
    echo "=== Downloading QEMU 11.1.3 tarball ==="
    curl -fSL -o "$ROOT/qemu-11.1.3.tar.xz.tmp" "https://download.qemu.org/qemu-11.1.3.tar.xz"
    mv "$ROOT/qemu-11.1.3.tar.xz.tmp" "$ROOT/qemu-11.1.3.tar.xz"
fi

# 1. Prepare clean patched QEMU source tree for Android
if [ ! -d "$SRC" ]; then
    echo "=== Extracting QEMU 11.1.3 for Android build ==="
    tar -xf "$ROOT/qemu-11.1.3.tar.xz" -C "$BUILD_ROOT"
    "$ROOT/scripts/apply-goldfish.sh" "$SRC"
    patch -d "$SRC" -p1 < "$ROOT/goldfish/qemu-11.1.3-android-ndk.patch"

    # Keep ONLY Android Goldfish board in arm-softmmu for minimal APK size
    cat > "$SRC/configs/devices/arm-softmmu/default.mak" << 'EOF'
CONFIG_I2C_DEVICES=n
CONFIG_PCI_DEVICES=n
CONFIG_TEST_DEVICES=n
CONFIG_ARM_VIRT=n
CONFIG_CUBIEBOARD=n
CONFIG_EXYNOS4=n
CONFIG_INTEGRATOR=n
CONFIG_FSL_IMX31=n
CONFIG_MUSICPAL=n
CONFIG_MPS3R=n
CONFIG_MUSCA=n
CONFIG_SX1=n
CONFIG_STELLARIS=n
CONFIG_STM32VLDISCOVERY=n
CONFIG_B_L475E_IOT01A=n
CONFIG_REALVIEW=n
CONFIG_VERSATILE=n
CONFIG_VEXPRESS=n
CONFIG_ZYNQ=n
CONFIG_NPCM7XX=n
CONFIG_COLLIE=n
CONFIG_ASPEED_SOC=n
CONFIG_NETDUINO2=n
CONFIG_NETDUINOPLUS2=n
CONFIG_OLIMEX_STM32_H405=n
CONFIG_MPS2=n
CONFIG_RASPI=n
CONFIG_DIGIC=n
CONFIG_SABRELITE=n
CONFIG_EMCRAFT_SF2=n
CONFIG_MICROBIT=n
CONFIG_FSL_IMX25=n
CONFIG_FSL_IMX7=n
CONFIG_FSL_IMX6UL=n
CONFIG_ALLWINNER_H3=n
CONFIG_ALLWINNER_R40=n
CONFIG_MAX78000_SOC=n
CONFIG_MAX78000FTHR=n
CONFIG_FSL_IMX8MP_EVK=n
CONFIG_FSL_IMX8MM_EVK=n
CONFIG_NPCM8XX=n
CONFIG_XLNX_ZYNQMP_ARM=n
CONFIG_XLNX_VERSAL=n
CONFIG_SBSA_REF=n
CONFIG_ARM_GICV5=y
CONFIG_ANDROID_GOLDFISH=y
EOF
else
    # Always refresh Goldfish sources in case we edited them
    cp "$ROOT"/goldfish/hw/arm/goldfish/* "$SRC/hw/arm/goldfish/"
fi

# 2. Configure QEMU for Android aarch64
if [ ! -f "$OUT/build.ninja" ]; then
    echo "=== Configuring QEMU 11.1.3 for Android arm64-v8a ==="
    rm -rf "$OUT"
    mkdir -p "$OUT"

    # Create cross-prefixed tool shims so QEMU configure finds llvm-* tools & static pkg-config
    mkdir -p "$BUILD_ROOT/bin"
    ln -sf "$TOOLCHAIN/bin/llvm-ar" "$BUILD_ROOT/bin/aarch64-linux-android-ar"
    ln -sf "$TOOLCHAIN/bin/llvm-ranlib" "$BUILD_ROOT/bin/aarch64-linux-android-ranlib"
    ln -sf "$TOOLCHAIN/bin/llvm-nm" "$BUILD_ROOT/bin/aarch64-linux-android-nm"
    ln -sf "$TOOLCHAIN/bin/llvm-strip" "$BUILD_ROOT/bin/aarch64-linux-android-strip"
    ln -sf "$TOOLCHAIN/bin/llvm-objcopy" "$BUILD_ROOT/bin/aarch64-linux-android-objcopy"
    ln -sf "$TOOLCHAIN/bin/ld.lld" "$BUILD_ROOT/bin/aarch64-linux-android-ld"
    cat > "$BUILD_ROOT/bin/aarch64-linux-android-pkg-config" << EOF
#!/bin/sh
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""
exec "$PKG_CONFIG_BIN" --static "\$@"
EOF
    chmod +x "$BUILD_ROOT/bin/aarch64-linux-android-pkg-config"

    (
        cd "$OUT"
        PATH="$BUILD_ROOT/bin:$PATH" \
        PKG_CONFIG="$BUILD_ROOT/bin/aarch64-linux-android-pkg-config" \
        "$SRC/configure" \
            --python="$PYTHON_BIN" \
            --disable-download \
            --cross-prefix="aarch64-linux-android-" \
            --cc="$CC" \
            --cxx="$CXX" \
            --host-cc="$HOST_CC_BIN" \
            --target-list=arm-softmmu \
            --enable-pie \
            --enable-tcg \
            --enable-vnc \
            --enable-slirp \
            --enable-fdt=system \
            --enable-coroutine-pool \
            --enable-trace-backends=nop \
            --audio-drv-list= \
            --disable-plugins \
            --disable-docs \
            --disable-tools \
            --disable-guest-agent \
            --disable-cocoa \
            --disable-dbus-display \
            --disable-sdl \
            --disable-gtk \
            --disable-spice \
            --disable-curl \
            --disable-libssh \
            --disable-libusb \
            --disable-gnutls \
            --disable-nettle \
            --disable-gcrypt \
            --disable-curses \
            --disable-capstone \
            --disable-vnc-sasl \
            --disable-vnc-jpeg \
            --disable-png \
            --disable-zstd \
            --disable-bzip2 \
            --disable-lzo \
            --disable-snappy \
            --disable-seccomp \
            --disable-kvm \
            --disable-xen \
            --disable-vhost-user \
            --disable-vhost-user-blk-server \
            --disable-virtfs \
            --disable-tpm \
            --disable-user \
            --disable-bsd-user \
            --disable-linux-user \
            --extra-cflags="-O2 -I$PREFIX/include" \
            --extra-ldflags="-L$PREFIX/lib -l:libandroid-setjmp.a -lintl -llog"
    )
fi

echo "=== Compiling qemu-system-arm for Android arm64-v8a ==="
ninja -C "$OUT" qemu-system-arm

cp "$OUT/qemu-system-arm" "$DIST/libqemu.so"
"$STRIP" --strip-unneeded "$DIST/libqemu.so"

echo "=== Built Android arm64-v8a binary ==="
ls -lh "$DIST/libqemu.so"
echo "=== Dynamic library dependencies (must be Android system libs only!) ==="
"$READELF" -d "$DIST/libqemu.so" | grep NEEDED
