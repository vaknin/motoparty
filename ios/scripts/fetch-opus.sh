#!/usr/bin/env bash
# Vendors libopus into Sources/COpus for the SwiftPM C target.
#
# Copies only the portable float build: CELT_SOURCES, SILK_SOURCES,
# SILK_SOURCES_FLOAT, OPUS_SOURCES and OPUS_SOURCES_FLOAT from the release's
# *_sources.mk lists, plus every header. No SIMD/asm sources and no dnn/ (deep
# PLC, DRED, OSCE stay off), so the same files build on Linux x86_64 (tests)
# and iOS arm64 (xtool) without per-arch flags. Build settings live in
# Sources/COpus/config.h, which this script does not touch.
#
# The result is committed, so builds need no network. Re-run this only to bump
# the Opus version (edit VERSION and SHA256 below).
set -euo pipefail

VERSION=1.5.2
SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
URL="https://downloads.xiph.org/releases/opus/opus-${VERSION}.tar.gz"

here="$(cd "$(dirname "$0")/.." && pwd)"
dest="$here/Sources/COpus"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading $URL"
curl -fsSL "$URL" -o "$work/opus.tar.gz"
echo "$SHA256  $work/opus.tar.gz" | sha256sum -c -
tar -xzf "$work/opus.tar.gz" -C "$work"
src="$work/opus-${VERSION}"

# Print the file list assigned to make variable $2 in file $1.
mk_list() {
  awk -v var="$2" '
    $1 == var && $2 == "=" { on = 1; next }
    on { line = $0; cont = sub(/\\[[:space:]]*$/, "", line)
         n = split(line, f); for (i = 1; i <= n; i++) print f[i]
         if (!cont) on = 0 }' "$1"
}

sources=$(
  mk_list "$src/celt_sources.mk" CELT_SOURCES
  mk_list "$src/silk_sources.mk" SILK_SOURCES
  mk_list "$src/silk_sources.mk" SILK_SOURCES_FLOAT
  mk_list "$src/opus_sources.mk" OPUS_SOURCES
  mk_list "$src/opus_sources.mk" OPUS_SOURCES_FLOAT
)

rm -rf "$dest/celt" "$dest/silk" "$dest/src"
rm -f "$dest"/include/opus*.h
mkdir -p "$dest/include"

count=0
for f in $sources; do
  mkdir -p "$dest/$(dirname "$f")"
  cp "$src/$f" "$dest/$f"
  count=$((count + 1))
done

# All private headers (arch-specific ones are only included behind macros we
# never define, but copying them keeps every #include resolvable).
(cd "$src" && find celt silk src -name '*.h' ! -path 'silk/fixed/*') | while read -r h; do
  mkdir -p "$dest/$(dirname "$h")"
  cp "$src/$h" "$dest/$h"
done

cp "$src"/include/opus*.h "$dest/include/"
cp "$src/COPYING" "$dest/COPYING"
echo "$VERSION" > "$dest/VERSION"

echo "Vendored libopus $VERSION: $count C files into $dest"
