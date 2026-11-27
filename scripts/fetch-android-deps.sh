#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/deps-android/tarballs"
mkdir -p "$SRC"

fetch() {
    local name="$1"
    local url="$2"
    if [ ! -f "$SRC/$name" ]; then
        echo "Downloading $name ..."
        curl -fSL -o "$SRC/$name.tmp" "$url"
        mv "$SRC/$name.tmp" "$SRC/$name"
    else
        echo "Already have $name"
    fi
}

fetch "libffi-3.4.6.tar.gz" "https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz"
fetch "pcre2-10.44.tar.bz2" "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.44/pcre2-10.44.tar.bz2"
fetch "glib-2.82.4.tar.xz" "https://download.gnome.org/sources/glib/2.82/glib-2.82.4.tar.xz"
fetch "pixman-0.44.2.tar.gz" "https://www.cairographics.org/releases/pixman-0.44.2.tar.gz"
fetch "libslirp-v4.9.0.tar.bz2" "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.9.0/libslirp-v4.9.0.tar.bz2"
fetch "dtc-1.7.2.tar.xz" "https://www.kernel.org/pub/software/utils/dtc/dtc-1.7.2.tar.xz"

echo "All Android dependency tarballs downloaded to $SRC"
