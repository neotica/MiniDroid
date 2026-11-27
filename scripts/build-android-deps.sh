#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="$ROOT/deps-android"
TAR="$DEPS/tarballs"
WORK="$DEPS/work"
PREFIX="$DEPS/prefix"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.1.12297006}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
API=28

export PATH="/opt/homebrew/bin:$TOOLCHAIN/bin:$PATH"
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
export PKG_CONFIG="/opt/homebrew/bin/pkg-config"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""

mkdir -p "$WORK" "$PREFIX/include" "$PREFIX/lib/pkgconfig"

# 1. Tiny no-op libintl stub (avoids pulling GNU gettext into Android APK)
if [ ! -f "$PREFIX/lib/libintl.a" ]; then
    echo "=== Building libintl stub ==="
    cat > "$PREFIX/include/libintl.h" << 'EOF'
#ifndef _ANDROID_STUB_LIBINTL_H
#define _ANDROID_STUB_LIBINTL_H
#ifdef __cplusplus
extern "C" {
#endif
char *gettext(const char *msgid) __attribute__((__format_arg__(1)));
char *dgettext(const char *domainname, const char *msgid) __attribute__((__format_arg__(2)));
char *dcgettext(const char *domainname, const char *msgid, int category) __attribute__((__format_arg__(2)));
char *ngettext(const char *msgid1, const char *msgid2, unsigned long int n) __attribute__((__format_arg__(1), __format_arg__(2)));
char *dngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long int n) __attribute__((__format_arg__(2), __format_arg__(3)));
char *dcngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long int n, int category) __attribute__((__format_arg__(2), __format_arg__(3)));
char *textdomain(const char *domainname);
char *bindtextdomain(const char *domainname, const char *dirname);
char *bind_textdomain_codeset(const char *domainname, const char *codeset);
#ifdef __cplusplus
}
#endif
#endif
EOF
    cat > "$WORK/libintl_stub.c" << 'EOF'
#include <libintl.h>
char *gettext(const char *m) { return (char *)m; }
char *dgettext(const char *d, const char *m) { (void)d; return (char *)m; }
char *dcgettext(const char *d, const char *m, int c) { (void)d; (void)c; return (char *)m; }
char *ngettext(const char *m1, const char *m2, unsigned long int n) { return (char *)(n == 1 ? m1 : m2); }
char *dngettext(const char *d, const char *m1, const char *m2, unsigned long int n) { (void)d; return (char *)(n == 1 ? m1 : m2); }
char *dcngettext(const char *d, const char *m1, const char *m2, unsigned long int n, int c) { (void)d; (void)c; return (char *)(n == 1 ? m1 : m2); }
char *textdomain(const char *d) { return (char *)(d ? d : "messages"); }
char *bindtextdomain(const char *d, const char *dir) { (void)d; return (char *)(dir ? dir : "/"); }
char *bind_textdomain_codeset(const char *d, const char *cs) { (void)d; return (char *)(cs ? cs : "UTF-8"); }
EOF
    "$CC" -O2 -fPIC -I"$PREFIX/include" -c "$WORK/libintl_stub.c" -o "$WORK/libintl_stub.o"
    "$AR" rcs "$PREFIX/lib/libintl.a" "$WORK/libintl_stub.o"
fi

# 2. Termux setjmp-aarch64 shim (fixes Bionic setjmp/longjmp under QEMU TCG)
if [ ! -f "$PREFIX/lib/libandroid-setjmp.a" ]; then
    echo "=== Building libandroid-setjmp.a ==="
    rm -rf "$WORK/setjmp-aarch64"
    mkdir -p "$WORK/setjmp-aarch64/private"
    cp "$ROOT/ref/termux-patches/setjmp-aarch64/setjmp.S" "$WORK/setjmp-aarch64/"
    for f in "$ROOT/ref/termux-patches/setjmp-aarch64"/private-*.h; do
        base="$(basename "$f")"
        cp "$f" "$WORK/setjmp-aarch64/private/${base#private-}"
    done
    (cd "$WORK/setjmp-aarch64" && "$CC" -O2 -fPIC -I. -c setjmp.S -o setjmp.o && "$AR" rcs "$PREFIX/lib/libandroid-setjmp.a" setjmp.o)
fi

# Generate Meson cross file
CROSS="$DEPS/android-arm64.cross"
cat > "$CROSS" << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkgconfig = '$PKG_CONFIG'
pkg-config = '$PKG_CONFIG'

[built-in options]
c_args = ['-O2', '-fPIC', '-I$PREFIX/include']
cpp_args = ['-O2', '-fPIC', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
cpp_link_args = ['-L$PREFIX/lib']

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# 3. libfdt (from dtc-1.7.2)
if [ ! -f "$PREFIX/lib/libfdt.a" ]; then
    echo "=== Building libfdt.a ==="
    rm -rf "$WORK/dtc-1.7.2"
    tar -xf "$TAR/dtc-1.7.2.tar.xz" -C "$WORK"
    (
        cd "$WORK/dtc-1.7.2/libfdt"
        objs=()
        for c in fdt.c fdt_ro.c fdt_wip.c fdt_sw.c fdt_rw.c fdt_strerror.c fdt_empty_tree.c fdt_addresses.c fdt_overlay.c fdt_check.c; do
            "$CC" -O2 -fPIC -I. -c "$c" -o "${c%.c}.o"
            objs+=("${c%.c}.o")
        done
        "$AR" rcs "$PREFIX/lib/libfdt.a" "${objs[@]}"
        cp fdt.h libfdt.h libfdt_env.h "$PREFIX/include/"
    )
fi

# 4. libffi-3.4.6
if [ ! -f "$PREFIX/lib/libffi.a" ]; then
    echo "=== Building libffi-3.4.6 ==="
    rm -rf "$WORK/libffi-3.4.6"
    tar -xf "$TAR/libffi-3.4.6.tar.gz" -C "$WORK"
    (
        cd "$WORK/libffi-3.4.6"
        CFLAGS="-O2 -fPIC" ./configure \
            --host=aarch64-linux-android \
            --prefix="$PREFIX" \
            --enable-static --disable-shared --disable-docs
        make -j8
        make install
    )
fi

# 5. pcre2-10.44
if [ ! -f "$PREFIX/lib/libpcre2-8.a" ]; then
    echo "=== Building pcre2-10.44 ==="
    rm -rf "$WORK/pcre2-10.44"
    tar -xf "$TAR/pcre2-10.44.tar.bz2" -C "$WORK"
    (
        cd "$WORK/pcre2-10.44"
        CFLAGS="-O2 -fPIC" ./configure \
            --host=aarch64-linux-android \
            --prefix="$PREFIX" \
            --enable-static --disable-shared \
            --enable-pcre2-8 --disable-jit --disable-pcre2grep-callout
        make -j8
        make install
    )
fi

# 6. glib-2.82.4
if [ ! -f "$PREFIX/lib/libglib-2.0.a" ]; then
    echo "=== Building glib-2.82.4 ==="
    rm -rf "$WORK/glib-2.82.4"
    tar -xf "$TAR/glib-2.82.4.tar.xz" -C "$WORK"
    (
        cd "$WORK/glib-2.82.4"
        meson setup build \
            --cross-file "$CROSS" \
            --prefix="$PREFIX" \
            --default-library=static \
            --wrap-mode=nodownload \
            -Dnls=disabled \
            -Dlibmount=disabled \
            -Dselinux=disabled \
            -Dtests=false \
            -Dinstalled_tests=false \
            -Dintrospection=disabled \
            -Dxattr=false \
            -Dlibelf=disabled \
            -Dglib_debug=disabled
        ninja -C build
        ninja -C build install
    )
fi

# 7. pixman-0.44.2
if [ ! -f "$PREFIX/lib/libpixman-1.a" ]; then
    echo "=== Building pixman-0.44.2 ==="
    rm -rf "$WORK/pixman-0.44.2"
    tar -xf "$TAR/pixman-0.44.2.tar.gz" -C "$WORK"
    (
        cd "$WORK/pixman-0.44.2"
        meson setup build \
            --cross-file "$CROSS" \
            --prefix="$PREFIX" \
            --default-library=static \
            -Dtests=disabled \
            -Ddemos=disabled \
            -Dgtk=disabled \
            -Dlibpng=disabled \
            -Dopenmp=disabled
        ninja -C build
        ninja -C build install
    )
fi

# 8. libslirp-v4.9.0
if [ ! -f "$PREFIX/lib/libslirp.a" ]; then
    echo "=== Building libslirp-v4.9.0 ==="
    rm -rf "$WORK/libslirp-v4.9.0"
    tar -xf "$TAR/libslirp-v4.9.0.tar.bz2" -C "$WORK"
    (
        cd "$WORK/libslirp-v4.9.0"
        meson setup build \
            --cross-file "$CROSS" \
            --prefix="$PREFIX" \
            --default-library=static
        ninja -C build
        ninja -C build install
    )
fi

echo "=== All static Android arm64 dependencies built cleanly! ==="
ls -lh "$PREFIX/lib/"*.a
