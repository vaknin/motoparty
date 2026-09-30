#!/usr/bin/env bash
# Draws the app icon (Icon.png, 1024x1024, no alpha): the Ride tab's
# "dot with radio waves" in the brand orange #FF7A2F on near-black #0E1013.
# Needs ImageMagick 7 (`magick`). xtool.yml names the result as `iconPath`.
set -euo pipefail
cd "$(dirname "$0")/.."

orange='#FF7A2F'
magick -size 1024x1024 "radial-gradient:#1B1F26-#0E1013" \
    -fill none -stroke "$orange" -strokewidth 58 \
    -draw "stroke-linecap round arc 342,342 682,682 -52,52" \
    -draw "stroke-linecap round arc 342,342 682,682 128,232" \
    -draw "stroke-linecap round arc 212,212 812,812 -48,48" \
    -draw "stroke-linecap round arc 212,212 812,812 132,228" \
    -fill "$orange" -stroke none -draw "circle 512,512 512,428" \
    -alpha off -depth 8 -define png:color-type=2 Icon.png
magick identify Icon.png
