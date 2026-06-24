#!/bin/bash
# Build FFmpeg static libs for Android, output to :native/src/main/cpp/prebuilt/<abi>/{lib,include}.
#
# Usage:
#   bash scripts/build_ffmpeg.sh           # default = arm64-v8a only
#   bash scripts/build_ffmpeg.sh all       # all three ABIs (arm64-v8a, armeabi-v7a, x86_64)
#   bash scripts/build_ffmpeg.sh arm64-v8a armeabi-v7a
#
# Prereqs (already verified on the dev box):
#   - NDK at D:/as_sdk/ndk/26.3.11579264 (with make.exe + yasm.exe in prebuilt/windows-x86_64/bin)
#   - FFmpeg source at .ffmpeg-src/FFmpeg-n6.1/
#   - perl + diff in PATH (Cygwin/Git Bash provides them)

set -e

# -------- Paths --------
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFMPEG_SRC="${PROJECT_ROOT}/.ffmpeg-src/FFmpeg-n6.1"
NDK_ROOT="${ANDROID_NDK_HOME:-D:/as_sdk/ndk/26.3.11579264}"
OUT_BASE="${PROJECT_ROOT}/native/src/main/cpp/prebuilt"

# On Windows, NDK ships make.exe and yasm.exe under prebuilt/windows-x86_64/bin —
# put it first in PATH so FFmpeg's configure picks them up.
HOST_TAG="windows-x86_64"
NDK_BIN_WIN="${NDK_ROOT}/prebuilt/${HOST_TAG}/bin"
TOOLCHAIN_WIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"

# Cygwin / MSYS bash needs Unix-style paths in PATH; convert via cygpath if available.
if command -v cygpath >/dev/null 2>&1; then
    NDK_BIN="$(cygpath -u "${NDK_BIN_WIN}")"
    TOOLCHAIN="$(cygpath -u "${TOOLCHAIN_WIN}")"
else
    NDK_BIN="${NDK_BIN_WIN}"
    TOOLCHAIN="${TOOLCHAIN_WIN}"
fi
export PATH="${NDK_BIN}:${TOOLCHAIN}/bin:${PATH}"

# FFmpeg configure creates a sanity-check script via mktemp. Cygwin inherits the Windows
# TEMP env var ("C:\Users\...") which loses backslashes once bash splits on path separators,
# producing "C:UsersPCAppDataLocalTemp/ffconf.XXXXX" — the "no such file" error. Force a
# Unix-style temp dir inside the project so the path is unambiguous.
export TMPDIR="${PROJECT_ROOT}/.ffmpeg-src/tmp"
export TMP="${TMPDIR}"
export TEMP="${TMPDIR}"
mkdir -p "${TMPDIR}"

# Sanity checks
if [ ! -d "${FFMPEG_SRC}" ]; then
    echo "ERROR: FFmpeg source not found at ${FFMPEG_SRC}" >&2
    echo "Download it first: curl -L https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n6.1.tar.gz | tar -xz -C .ffmpeg-src" >&2
    exit 1
fi
if [ ! -x "${NDK_BIN}/make.exe" ] && ! command -v make >/dev/null 2>&1; then
    echo "ERROR: make not found in PATH or NDK prebuilt" >&2
    exit 1
fi
if ! command -v yasm >/dev/null 2>&1; then
    echo "WARNING: yasm not found — assembly will be disabled (slow!)"
fi

# -------- ABI matrix --------
declare -A ANDROID_API_FOR
ANDROID_API_FOR[arm64-v8a]=24
ANDROID_API_FOR[armeabi-v7a]=24
ANDROID_API_FOR[x86_64]=24

declare -A ARCH_FOR
ARCH_FOR[arm64-v8a]=aarch64
ARCH_FOR[armeabi-v7a]=arm
ARCH_FOR[x86_64]=x86_64

declare -A CPU_FOR
CPU_FOR[arm64-v8a]=armv8-a
CPU_FOR[armeabi-v7a]=armv7-a
CPU_FOR[x86_64]=x86_64

declare -A CLANG_TARGET_FOR
CLANG_TARGET_FOR[arm64-v8a]=aarch64-linux-android
CLANG_TARGET_FOR[armeabi-v7a]=armv7a-linux-androideabi
CLANG_TARGET_FOR[x86_64]=x86_64-linux-android

# -------- Build one ABI --------
build_one() {
    local ABI=$1
    local ARCH=${ARCH_FOR[$ABI]}
    local CPU=${CPU_FOR[$ABI]}
    local CLANG_TARGET=${CLANG_TARGET_FOR[$ABI]}
    local API=${ANDROID_API_FOR[$ABI]}

    local PREFIX="${OUT_BASE}/${ABI}"
    local BUILD_DIR="${PROJECT_ROOT}/.ffmpeg-src/build/${ABI}"

    echo
    echo "================================================================"
    echo "  Building FFmpeg for ${ABI} (API ${API})"
    echo "================================================================"
    echo "  Source : ${FFMPEG_SRC}"
    echo "  Build  : ${BUILD_DIR}"
    echo "  Output : ${PREFIX}"
    echo

    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"

    # FFmpeg cross-compile: use clang from NDK, target Android API level.
    # We disable everything we don't need (programs, doc, debug) to keep
    # libavcodec.a small (~12MB for arm64-v8a with our codec list).
    local CC="${TOOLCHAIN}/bin/${CLANG_TARGET}${API}-clang"
    # armv7 has a different sysroot file name convention
    if [ "$ABI" = "armeabi-v7a" ]; then
        CC="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API}-clang"
    fi
    # Windows host: NDK ships them as .cmd; bash needs the .cmd suffix.
    if [ ! -x "${CC}" ] && [ -x "${CC}.cmd" ]; then
        CC="${CC}.cmd"
    fi

    local EXTRA_CFLAGS="-O3 -fpic"
    local EXTRA_LDFLAGS=""
    if [ "$ABI" = "armeabi-v7a" ]; then
        EXTRA_CFLAGS="${EXTRA_CFLAGS} -mfpu=neon -mfloat-abi=softfp"
    fi

    # NDK r23+ ships unified llvm-* binutils, no per-arch prefix. FFmpeg configure
    # tries to find bare "nm" / "ar" / "ranlib" / "strip" and fails — pass them explicitly.
    local NM="${TOOLCHAIN}/bin/llvm-nm"
    local AR="${TOOLCHAIN}/bin/llvm-ar"
    local RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    local STRIP="${TOOLCHAIN}/bin/llvm-strip"

    "${FFMPEG_SRC}/configure" \
        --prefix="${PREFIX}" \
        --target-os=android \
        --arch=${ARCH} \
        --cpu=${CPU} \
        --cc="${CC}" \
        --nm="${NM}" \
        --ar="${AR}" \
        --ranlib="${RANLIB}" \
        --strip="${STRIP}" \
        --enable-cross-compile \
        --sysroot="${TOOLCHAIN}/sysroot" \
        --extra-cflags="${EXTRA_CFLAGS}" \
        --extra-ldflags="${EXTRA_LDFLAGS}" \
        --enable-static \
        --disable-shared \
        --enable-pic \
        --disable-programs \
        --disable-doc \
        --disable-debug \
        --disable-symver \
        --disable-avdevice \
        --disable-postproc \
        --disable-network \
        --disable-encoders \
        --disable-muxers \
        --disable-bsfs \
        --disable-filters \
        --disable-devices \
        --disable-everything \
        --enable-decoders \
        --enable-demuxers \
        --enable-parsers \
        --enable-protocol=file \
        --enable-protocol=pipe \
        --enable-swresample \
        --enable-swscale

    # NDK make.exe is single-threaded-friendly; use -j for speed but cap to avoid OOM.
    local JOBS=$(nproc 2>/dev/null || echo 4)
    make -j${JOBS}
    make install

    echo
    echo "  ✓ ${ABI} done. Static libs:"
    ls -lh "${PREFIX}/lib"/*.a | awk '{ printf "    %s  %s\n", $5, $NF }'
}

# -------- Driver --------
if [ $# -eq 0 ]; then
    ABIS=("arm64-v8a")
elif [ "$1" = "all" ]; then
    ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
else
    ABIS=("$@")
fi

for ABI in "${ABIS[@]}"; do
    build_one "${ABI}"
done

echo
echo "================================================================"
echo "  All done. Built ABIs: ${ABIS[*]}"
echo "  Run ./gradlew :native:assembleDebug to repackage the .so."
echo "================================================================"
