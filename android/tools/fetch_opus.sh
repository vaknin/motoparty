#!/usr/bin/env bash
# Downloads the libopus release tarball, verifies it, and unpacks it into
# app/src/main/cpp/opus (git-ignored). CMake builds it as a static library linked into
# libmotoparty_opus.so. Re-run after changing the version below.
set -euo pipefail

VERSION=1.5.2
SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
URL="https://downloads.xiph.org/releases/opus/opus-${VERSION}.tar.gz"

here="$(cd "$(dirname "$0")/.." && pwd)"
dest="$here/app/src/main/cpp/opus"

if [[ -f "$dest/VERSION.motoparty" && "$(cat "$dest/VERSION.motoparty")" == "$VERSION" ]]; then
    echo "libopus $VERSION already present in $dest"
    exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "Downloading $URL"
curl -fsSL -o "$tmp/opus.tar.gz" "$URL"
echo "$SHA256  $tmp/opus.tar.gz" | sha256sum -c -
rm -rf "$dest"
mkdir -p "$dest"
tar -xzf "$tmp/opus.tar.gz" -C "$dest" --strip-components=1
echo "$VERSION" > "$dest/VERSION.motoparty"
echo "libopus $VERSION unpacked into $dest"
