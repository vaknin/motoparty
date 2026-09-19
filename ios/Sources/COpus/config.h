/* Build configuration for the vendored libopus (see scripts/fetch-opus.sh).
 *
 * Portable float build, identical on Linux x86_64 (swift test) and iOS arm64
 * (xtool): no FIXED_POINT, no SIMD / asm / run-time CPU detection, no DNN
 * features (deep PLC, DRED, OSCE). 16 kHz mono VOIP needs a tiny fraction of
 * one core, so the missing NEON paths do not matter.
 *
 * Every Opus .c file does `#include "config.h"` under HAVE_CONFIG_H, which
 * Package.swift defines; this file is found through headerSearchPath("."). */
#ifndef MOTOPARTY_OPUS_CONFIG_H
#define MOTOPARTY_OPUS_CONFIG_H

#define OPUS_BUILD 1

/* Scratch arrays: alloca() is available as <alloca.h> on glibc and Darwin. */
#define USE_ALLOCA 1
#define HAVE_ALLOCA_H 1

/* Fast float->int rounding in celt/float_cast.h. */
#define HAVE_LRINTF 1
#define HAVE_LRINT 1

#define HAVE_STDINT_H 1
#define HAVE_DLFCN_H 1

#define PACKAGE_VERSION "1.5.2"

#endif
