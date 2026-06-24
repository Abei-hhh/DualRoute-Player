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

# Cygwin / MSYS bash needs Unix-style paths in $PATH; but NDK's make.exe is native Windows
# and chokes on "/d/..." paths inside Makefiles. We split the two:
#   - PATH (for shell to find binaries) = Unix-style via cygpath -u
#   - Paths passed to configure / make targets = mixed Windows style "D:/..." via cygpath -m
#     so the generated Makefile is consumable by NDK make.exe
if command -v cygpath >/dev/null 2>&1; then
    NDK_BIN="$(cygpath -u "${NDK_BIN_WIN}")"
    TOOLCHAIN="$(cygpath -u "${TOOLCHAIN_WIN}")"
    # mixed-style versions for passing into FFmpeg configure (so it embeds Windows-ish paths
    # into the resulting Makefile / config.h)
    TOOLCHAIN_MIXED="$(cygpath -m "${TOOLCHAIN_WIN}")"
    PROJECT_ROOT_MIXED="$(cygpath -m "${PROJECT_ROOT}")"
    FFMPEG_SRC_MIXED="$(cygpath -m "${FFMPEG_SRC}")"
else
    NDK_BIN="${NDK_BIN_WIN}"
    TOOLCHAIN="${TOOLCHAIN_WIN}"
    TOOLCHAIN_MIXED="${TOOLCHAIN_WIN}"
    PROJECT_ROOT_MIXED="${PROJECT_ROOT}"
    FFMPEG_SRC_MIXED="${FFMPEG_SRC}"
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

# Patch FFmpeg's library.mak so the `ar` invocation feeds the (huge) object list through a
# GNU-make $(file >…) response file instead of expanding inline. On Windows the inline cmdline
# easily blows past the 8191-char limit and llvm-ar reports cryptic errors like
# "libav: No such file or directory" (where "libav" is the front half of a chopped path).
# $(file …) is a GNU make 4.0+ builtin — no shell involvement, no quoting headaches.
# Idempotent: marker comment prevents re-patching.
LIBRARY_MAK="${FFMPEG_SRC}/ffbuild/library.mak"
if [ -f "${LIBRARY_MAK}" ] && ! grep -q "splitplay-rsp-patch" "${LIBRARY_MAK}"; then
    echo "Patching ${LIBRARY_MAK} for Windows cmdline limits..."
    # Two patches, both targeting the 8191-char Windows process cmdline limit. Use perl
    # because sed-i quoting on multi-line / makefile-escape strings is a rabbit hole.
    #
    # 1) AR — replace `$(AR) $(ARFLAGS) $(AR_O) $^` with a $(file …) response-file variant.
    #    GNU make 4.0+ $(file) builtin writes the object list without invoking the shell;
    #    hundreds of obj paths never hit the cmdline.
    # 2) INSTALL header lists — for-loop one install at a time. NDK make uses sh.exe as
    #    SHELL, so the loop runs under Cygwin sh.
    cp "${LIBRARY_MAK}" "${LIBRARY_MAK}.bak"
    perl -i -pe '
        # 1) AR rule
        if (/^\t\$\(AR\) \$\(ARFLAGS\) \$\(AR_O\) \$\^/) {
            $_ = "\t\$(file >\$(AR_O).rsp,\$^) # splitplay-rsp-patch\n" .
                 "\t\$(AR) \$(ARFLAGS) \$(AR_O) \@\$(AR_O).rsp\n";
        }
        # 2) INSTALL header rule: one install per file via sh for-loop.
        #
        # Two layers of trickery:
        #  - This rule sits inside `define RULES ... endef` consumed by `$(eval $(RULES))`,
        #    so each `$` going through make twice needs `$$`; each `$` for sh needs `$$$$`.
        #    `$^` (prereq list) needs to survive both rounds → `$$^`.
        #  - We bypass the `$(INSTALL)` make variable. FFmpeg redefines it to
        #    `@printf "INSTALL\t%s\n" $(^:/%=%); install`, which expands the full prereq
        #    list inline. On Windows that re-blows the 8191-char cmdline AND leaks a literal
        #    "@" into sh (since the `@` is part of the macro body, not a make recipe prefix).
        #    Plain `install` (bare command) sidesteps both.
        elsif (/^\t\$\$\(INSTALL\) -m 644 \$\$\^ "\$\(INCINSTDIR\)"/) {
            $_ = "\t\@for h in \$\$^; do install -m 644 \"\$\$\$\$h\" \"\$(INCINSTDIR)\" || exit 1; done\n";
        }
    ' "${LIBRARY_MAK}"
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
    # Build dirs occasionally get locked by Windows file handles (explorer / lingering bash).
    # Use a PID-suffixed name so a stuck dir doesn't block the next attempt; cleanup is
    # best-effort, and the disk hit (a few hundred MB per attempt) is acceptable while iterating.
    local BUILD_DIR="${PROJECT_ROOT}/.ffmpeg-src/build/${ABI}-$$"

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
    # Mixed-path so Makefile embeds Windows-style invocations consumable by NDK make.exe.
    local CC="${TOOLCHAIN_MIXED}/bin/${CLANG_TARGET}${API}-clang.cmd"
    if [ "$ABI" = "armeabi-v7a" ]; then
        CC="${TOOLCHAIN_MIXED}/bin/armv7a-linux-androideabi${API}-clang.cmd"
    fi

    local EXTRA_CFLAGS="-O3 -fpic"
    local EXTRA_LDFLAGS=""
    if [ "$ABI" = "armeabi-v7a" ]; then
        EXTRA_CFLAGS="${EXTRA_CFLAGS} -mfpu=neon -mfloat-abi=softfp"
    fi

    # NDK r23+ ships unified llvm-* binutils, no per-arch prefix. FFmpeg configure
    # tries to find bare "nm" / "ar" / "ranlib" / "strip" and fails — pass them explicitly.
    # Use mixed Windows paths so the generated Makefile is digestible by NDK's native make.exe.
    local NM="${TOOLCHAIN_MIXED}/bin/llvm-nm.exe"
    local AR="${TOOLCHAIN_MIXED}/bin/llvm-ar.exe"
    local RANLIB="${TOOLCHAIN_MIXED}/bin/llvm-ranlib.exe"
    local STRIP="${TOOLCHAIN_MIXED}/bin/llvm-strip.exe"
    local PREFIX_MIXED="$(cygpath -m "${PREFIX}")"

    # Likewise, invoke configure via the mixed path so $0 (= srcdir) is Windows-style
    # and gets embedded as such throughout the generated Makefile / config.mak.
    "${FFMPEG_SRC_MIXED}/configure" \
        --prefix="${PREFIX_MIXED}" \
        --target-os=android \
        --arch=${ARCH} \
        --cpu=${CPU} \
        --cc="${CC}" \
        --nm="${NM}" \
        --ar="${AR}" \
        --ranlib="${RANLIB}" \
        --strip="${STRIP}" \
        --enable-cross-compile \
        --sysroot="${TOOLCHAIN_MIXED}/sysroot" \
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
        --enable-swscale \
        --disable-asm

    # FFmpeg configure rewrites srcdir to Cygwin-style ("/d/...") inside Makefile + config.mak,
    # which NDK's native make.exe can't open. Patch them to mixed Windows paths in place so the
    # subsequent make can chew through them.
    if grep -q "^include /" Makefile 2>/dev/null; then
        sed -i 's|/d/as_work|D:/as_work|g; s|/c/|C:/|g' Makefile ffbuild/config.mak
    fi

    # Use NDK's bundled make.exe by absolute path so we don't accidentally pick a Cygwin one
    # that processes Unix paths differently. -j enables parallel builds; arm64 host with 8+ cores
    # finishes in ~5 min.
    local NDK_MAKE="${NDK_BIN_WIN}/make.exe"
    local JOBS=$(nproc 2>/dev/null || echo 4)
    "${NDK_MAKE}" -j${JOBS}
    "${NDK_MAKE}" install

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
