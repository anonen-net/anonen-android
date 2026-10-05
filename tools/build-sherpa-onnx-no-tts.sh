#!/usr/bin/env bash
# Builds the sherpa-onnx JNI library for Android without text-to-speech and
# repackages the upstream AAR around it.
#
# The AAR published by the sherpa-onnx project links espeak-ng, which is
# licensed under GPL-3.0-or-later, into libsherpa-onnx-jni.so. The project's
# own build scripts accept SHERPA_ONNX_ENABLE_TTS=OFF, which leaves espeak-ng
# and piper-phonemize out. Upstream publishes such builds for other platforms
# but not for Android, so this script produces one.
#
# The build itself is upstream's: the release tag is checked out and
# build-android-*.sh is run unmodified, with text-to-speech switched off
# through the environment variable those scripts read. Everything else in the
# AAR (classes.jar, manifest, libonnxruntime.so) is copied from the upstream
# release byte for byte. Only arm64-v8a and armeabi-v7a are built; the other
# ABIs and the C API libraries of the upstream AAR are left out, since a JNI
# consumer needs neither.
#
# The result does not depend on the directory it is built in: the same inputs
# and the same toolchain give the same AAR, byte for byte. The hash of that
# AAR is recorded below (EXPECTED_AAR_SHA256) and compared at the end.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk/29.0.14206865 \
#     build-sherpa-onnx-no-tts.sh OUTPUT.aar
#
# Environment:
#   ANDROID_NDK_HOME  Android NDK r29 (29.0.14206865), the release upstream
#                     built this tag with. ANDROID_NDK is accepted as well.
#   UPSTREAM_AAR      Path to the published sherpa-onnx AAR. Downloaded into
#                     the work directory when unset.
#   WORK_DIR          Scratch directory; its path must not contain
#                     whitespace. A temporary one is created and removed when
#                     unset. Downloads are kept in WORK_DIR/downloads between
#                     runs; WORK_DIR/src is recreated on every run. The output
#                     path must not be inside it (nor a link — one level is
#                     followed — into it, nor the same file as its staging copy):
#                     the build writes there, and the script refuses to start
#                     otherwise. With REQUIRE_EXPECTED_AAR=1 a link at the
#                     output path that is not the recorded build is removed
#                     first, before that check.
#   REQUIRE_EXPECTED_AAR
#                     Set to 1 to fail, and write nothing, when the AAR differs
#                     from the recorded hash. However the script stops, the
#                     output path then holds the recorded build or nothing: a
#                     file already there is removed before anything else is
#                     done, unless it is the recorded build. When that cannot
#                     be told (a directory, a dangling link, a file that
#                     cannot be read) the script stops at once and changes
#                     nothing. Unset, a difference is reported and the AAR is
#                     still written: the content checks have passed, which
#                     shows what is not in it, not that it is the recorded
#                     build.
#
# Requirements: bash, git, curl, gzip, python3, cmake 3.x (upstream's CMake
# files do not configure with cmake 4). GNU make is taken from the NDK when it
# is not on PATH.
set -euo pipefail

SHERPA_ONNX_VERSION=1.13.3
SHERPA_ONNX_REPO=https://github.com/k2-fsa/sherpa-onnx
SHERPA_ONNX_COMMIT=330609dab49be6ee8b30702918ca7abbbad1286a
UPSTREAM_AAR_SHA256=243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6

# The AAR this script is expected to produce, built from the inputs pinned in
# this file with NDK 29.0.14206865 and cmake 3.31.6.
EXPECTED_AAR_SHA256=bb0e55ade21be1db179222693c0a400378b557dd9942933a64c1c6a625c58fc6
# The two libraries in it that this script compiles, for telling which one a
# differing AAR differs in. Everything else in the AAR is copied from inputs
# that are pinned by hash.
EXPECTED_JNI_SHA256=(
  "arm64-v8a 3fb6a8be6853740c7f34423c991add9725f0b0b5715fe094ea87a5b1e7232768"
  "armeabi-v7a 3bf84b53ae813fc1767676a3d179fc2f32aad19642ee888b022d3539b8780a55"
)

# The prebuilt ONNX Runtime the upstream build scripts link against: the
# com.microsoft.onnxruntime:onnxruntime-android AAR as published on Maven
# Central. (Upstream's scripts download the same file, under another name,
# from a GitHub release; the hash is the same.)
ONNXRUNTIME_VERSION=1.24.3
ONNXRUNTIME_URL=https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.aar
ONNXRUNTIME_SHA256=67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce

# Eigen is the one dependency upstream's CMake fetches from outside GitHub.
# The archive name and hash below are the ones cmake/eigen.cmake pins.
EIGEN_VERSION=5.0.1
EIGEN_URL=https://gitlab.com/libeigen/eigen/-/archive/${EIGEN_VERSION}/eigen-${EIGEN_VERSION}.tar.gz
EIGEN_SHA256=e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec
EIGEN_MIRROR=https://github.com/eigen-mirror/eigen
EIGEN_COMMIT=bc3b39870ecb690a623a3f49149a358b95c5781d

NDK_REVISION=29.0.14206865

# ABI name, upstream build script, build directory that script creates.
TARGETS=(
  "arm64-v8a build-android-arm64-v8a.sh build-android-arm64-v8a"
  "armeabi-v7a build-android-armv7-eabi.sh build-android-armv7-eabi"
)

# Strings that only occur when espeak-ng or piper-phonemize is linked in.
# "espeak" alone is not usable: it is a substring of "wespeaker".
TTS_MARKERS='espeak-ng|espeak_|ESPEAK_|phontab|phonindex|intonations|piper'

# JNI entry points that exist only in a build with text-to-speech.
TTS_SYMBOLS='^Java_com_k2fsa_sherpa_onnx_(OfflineTts|GeneratedAudio)_'

# Shared libraries the JNI library may depend on: ONNX Runtime, which is
# packaged next to it, and libraries that are part of Android itself.
ALLOWED_NEEDED='^(libonnxruntime|libandroid|liblog|libm|libdl|libc)\.so$'

# The third-party source trees that objects of the JNI library are compiled
# from, as upstream's CMake names them. THIRD-PARTY-NOTICES.md carries the
# license of each. The list is compared with what the compiler actually read;
# when upstream adds or drops a dependency the build stops here, so that the
# notices are brought in line before this list is.
COMPILED_DEPENDENCIES='eigen hclust_cpp json kaldi_decoder kaldi_native_fbank kaldifst kissfft openfst simple-sentencepiece'

# Everything the upstream AAR holds outside jni/. These entries are copied
# into the new AAR as they are; anything else is not expected and stops the
# build.
UPSTREAM_AAR_ENTRIES='AndroidManifest.xml META-INF/com/android/build/gradle/aar-metadata.properties R.txt classes.jar proguard.txt'

die() {
  echo "error: $*" >&2
  exit 1
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

check_sha256() {
  local file="$1" want="$2" got
  got="$(sha256_of "$file")"
  [ "$got" = "$want" ] || die "sha256 mismatch for $file: expected $want, got $got"
}

download() {
  local url="$1" sha256="$2" dest="$3"
  if [ ! -f "$dest" ]; then
    curl -fsSL -o "$dest.part" "$url" || die "download failed: $url"
    mv "$dest.part" "$dest"
  fi
  check_sha256 "$dest" "$sha256"
}

# Fetches the Eigen archive. When GitLab cannot be reached the same archive is
# rebuilt from the GitHub mirror; the hash check holds either way.
fetch_eigen() {
  local dest="$1" mirror="$work/eigen-mirror"
  if [ ! -f "$dest" ]; then
    if ! curl -fsSL -o "$dest.part" "$EIGEN_URL"; then
      echo "==> $EIGEN_URL is unreachable; rebuilding the archive from $EIGEN_MIRROR"
      rm -rf "$dest.part" "$mirror"
      git -c advice.detachedHead=false clone -q --depth 1 --branch "$EIGEN_VERSION" "$EIGEN_MIRROR" "$mirror"
      [ "$(git -C "$mirror" rev-parse HEAD)" = "$EIGEN_COMMIT" ] ||
        die "unexpected commit for Eigen $EIGEN_VERSION in $EIGEN_MIRROR"
      git -C "$mirror" archive --format=tar --prefix="eigen-$EIGEN_VERSION/" "$EIGEN_VERSION" |
        gzip -n >"$dest.part"
    fi
    mv "$dest.part" "$dest"
  fi
  check_sha256 "$dest" "$EIGEN_SHA256"
}

# Names of the third-party source trees that a compiled object depends on,
# read from the dependency files the compiler wrote next to each object.
compiled_dependencies() {
  python3 - "$1" <<'PY'
import glob
import os
import re
import sys

names = set()
depfiles = glob.glob(os.path.join(sys.argv[1], "**", "*.o.d"), recursive=True)
if len(depfiles) < 100:
    sys.exit(f"only {len(depfiles)} dependency files under {sys.argv[1]}")
for depfile in depfiles:
    with open(depfile, errors="replace") as f:
        names.update(re.findall(r"(?:^|[/\s])_deps/([^/\s]+)-src/", f.read(), re.M))
print(" ".join(sorted(names)))
PY
}

jni_symbols() {
  "$llvm_bin/llvm-nm" -D --defined-only "$1" | awk '$NF ~ /^Java_/ { print $NF }' | LC_ALL=C sort -u
}

needed_libraries() {
  "$llvm_bin/llvm-readelf" -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]$/\1/p'
}

# Fails unless the rebuilt library is free of text-to-speech code, offers every
# other JNI entry point the upstream library offers, depends only on the
# expected libraries, and carries no path of the machine it was built on.
verify_library() {
  local abi="$1" built="$2" upstream="$3"
  local built_symbols upstream_symbols missing needed unexpected
  if LC_ALL=C grep -a -q -E "$TTS_MARKERS" "$built"; then
    die "$abi: text-to-speech code is still present in $built"
  fi
  built_symbols="$(jni_symbols "$built")"
  upstream_symbols="$(jni_symbols "$upstream")"
  [ -n "$built_symbols" ] || die "$abi: no JNI entry points found in $built"
  [ -n "$upstream_symbols" ] || die "$abi: no JNI entry points found in $upstream"
  if grep -q -E "$TTS_SYMBOLS" <<<"$built_symbols"; then
    die "$abi: text-to-speech JNI entry points are still exported by $built"
  fi
  missing="$(LC_ALL=C comm -23 <(grep -v -E "$TTS_SYMBOLS" <<<"$upstream_symbols") <(printf '%s\n' "$built_symbols"))"
  [ -z "$missing" ] || die "$abi: JNI entry points missing from the rebuilt library: $missing"
  needed="$(needed_libraries "$built")"
  [ -n "$needed" ] || die "$abi: could not read the dependencies of $built"
  unexpected="$(grep -v -E "$ALLOWED_NEEDED" <<<"$needed" || true)"
  [ -z "$unexpected" ] || die "$abi: unexpected shared library dependency: $unexpected"
  if LC_ALL=C grep -a -q -F "$work" "$built"; then
    die "$abi: the build directory is embedded in $built"
  fi
}

[ $# -eq 1 ] || die "usage: $0 OUTPUT.aar"
mkdir -p "$(dirname "$1")"
output="$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")"
if [ -n "${UPSTREAM_AAR:-}" ] && [ "$output" -ef "$UPSTREAM_AAR" ]; then
  die "the output path is the upstream AAR given as UPSTREAM_AAR: $output"
fi
# A directory (or a link to one) cannot hold the AAR: the copy at the end would land
# inside it and the script would still print "==> <output>" as if it had been written.
[ ! -d "$output" ] || die "the output path is a directory: $output"

# REQUIRE_EXPECTED_AAR=1: whatever is already at the output path is dealt with
# here, before anything can fail, so that every way of stopping below leaves
# the same state there: the recorded build, or nothing that a later Gradle run
# would pick up as if it had come from this run.
if [ "${REQUIRE_EXPECTED_AAR:-}" = "1" ] && { [ -e "$output" ] || [ -L "$output" ]; }; then
  if [ ! -f "$output" ] || [ ! -r "$output" ]; then
    die "REQUIRE_EXPECTED_AAR=1: cannot tell whether $output is the recorded build (it is not a file that can be read). Nothing was changed; move it away and run again."
  fi
  if [ "$(sha256_of "$output")" = "$EXPECTED_AAR_SHA256" ]; then
    echo "==> $output already holds the recorded build; it stays unless this run replaces it"
  elif [ -L "$output" ]; then
    rm -f -- "$output"
    echo "==> removed the link $output: what it pointed to is not the recorded build (the target was left alone)"
  else
    rm -f -- "$output"
    echo "==> removed $output: it was not the recorded build"
  fi
fi

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
[ -n "$ndk" ] || die "set ANDROID_NDK_HOME to Android NDK $NDK_REVISION"
[ -f "$ndk/source.properties" ] || die "not an Android NDK: $ndk"
ndk="$(cd "$ndk" && pwd -P)"
grep -q "^Pkg.Revision = $NDK_REVISION\$" "$ndk/source.properties" ||
  die "Android NDK $NDK_REVISION is required; found $(grep '^Pkg.Revision' "$ndk/source.properties")"
llvm_bin="$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin)"
[ -x "$llvm_bin/llvm-nm" ] || die "llvm-nm not found under $ndk"

for tool in git curl gzip python3 cmake; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
cmake_version="$(cmake --version | sed -n '1s/^cmake version //p')" || die "cmake --version failed"
case "$cmake_version" in
3.*) ;;
*) die "cmake 3.x is required; found ${cmake_version:-an unknown version}" ;;
esac
if ! command -v make >/dev/null 2>&1; then
  ndk_make="$(echo "$ndk"/prebuilt/*/bin)"
  [ -x "$ndk_make/make" ] || die "make is required"
  PATH="$ndk_make:$PATH"
fi

made_work=""
if [ -n "${WORK_DIR:-}" ]; then
  # Remembered so that a refusal below can take back a directory this run created —
  # a WORK_DIR that is the output path itself would otherwise leave a directory there.
  [ -e "$WORK_DIR" ] || made_work=1
  mkdir -p "$WORK_DIR"
  work="$(cd "$WORK_DIR" && pwd -P)"
  keep_work=1
else
  work="$(cd "$(mktemp -d)" && pwd -P)"
  keep_work=""
fi
# Whatever stops the script, the copy made next to the output path (see place) is
# not left behind; the work directory is kept only when the caller chose it.
cleanup() {
  rm -f -- "$output.tmp.$$"
  [ -n "$keep_work" ] || rm -rf "$work"
}
trap cleanup EXIT
# The output path must not lead into the work directory: the build writes there, and
# a path under it — or a link to one — could be turned into a directory, or overwritten,
# by the build itself before the AAR is put in place. One level of link is followed.
leads_into_work() {
  case "$1" in "$work" | "$work"/*) return 0 ;; esac
  if [ -L "$1" ]; then
    local target
    target="$(readlink -- "$1")"
    case "$target" in /*) ;; *) target="$(dirname -- "$1")/$target" ;; esac
    if [ -d "$(dirname -- "$target")" ]; then
      target="$(cd "$(dirname -- "$target")" && pwd -P)/$(basename -- "$target")"
    fi
    case "$target" in "$work" | "$work"/*) return 0 ;; esac
  fi
  return 1
}
# The staging copy is named here already, so that the same file (a hard link, a link
# through a link) is refused before the six-minute build rather than after it.
staged="$work/staged/sherpa-onnx-$SHERPA_ONNX_VERSION-no-tts.aar"
refuse_output() {
  # Only the directory this run created at the output path itself is taken back: a
  # WORK_DIR spelled through a missing component (`missing/../w`) resolves to an
  # existing directory, which is not this run's to remove.
  if [ -n "$made_work" ] && [ "$work" = "$output" ]; then
    rmdir -- "$work" 2>/dev/null || true
  fi
  die "$1"
}
if leads_into_work "$output"; then
  refuse_output "the output path leads into the work directory: $output"
fi
[ "$staged" != "$output" ] || refuse_output "the output path is this script's own staging file: $output"
if [ -e "$output" ] && [ "$output" -ef "$staged" ]; then
  refuse_output "the output path is the same file as this script's own staging file (a link to it): $output"
fi
# The upstream build scripts do not quote the paths they derive from the
# current directory.
case "$work" in
*[[:space:]]*) refuse_output "the work directory must not contain whitespace: $work" ;;
esac
downloads="$work/downloads"
src="$work/src"
echo "==> cmake $cmake_version, NDK $NDK_REVISION, work directory $work"
mkdir -p "$downloads"
rm -rf "$src" "$work/upstream-aar" "$work/onnxruntime"

aar="${UPSTREAM_AAR:-$downloads/sherpa-onnx-$SHERPA_ONNX_VERSION.aar}"
if [ -z "${UPSTREAM_AAR:-}" ]; then
  download "$SHERPA_ONNX_REPO/releases/download/v$SHERPA_ONNX_VERSION/sherpa-onnx-$SHERPA_ONNX_VERSION.aar" \
    "$UPSTREAM_AAR_SHA256" "$aar"
fi
check_sha256 "$aar" "$UPSTREAM_AAR_SHA256"
download "$ONNXRUNTIME_URL" "$ONNXRUNTIME_SHA256" "$downloads/onnxruntime-android-$ONNXRUNTIME_VERSION.aar"

python3 - "$aar" "$work/upstream-aar" "$downloads/onnxruntime-android-$ONNXRUNTIME_VERSION.aar" "$work/onnxruntime" <<'PY'
import sys
import zipfile

for archive, destination in zip(sys.argv[1::2], sys.argv[2::2]):
    with zipfile.ZipFile(archive) as z:
        z.extractall(destination)
PY

# The upstream AAR holds nothing outside jni/ beyond the entries listed above.
aar_entries="$(
  python3 - "$aar" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as z:
    names = [n for n in z.namelist() if not n.endswith("/") and not n.startswith("jni/")]
print(" ".join(sorted(names)))
PY
)"
[ "$aar_entries" = "$UPSTREAM_AAR_ENTRIES" ] ||
  die "unexpected entries in the upstream AAR: $aar_entries"

git -c advice.detachedHead=false clone -q --depth 1 --branch "v$SHERPA_ONNX_VERSION" "$SHERPA_ONNX_REPO" "$src"
[ "$(git -C "$src" rev-parse HEAD)" = "$SHERPA_ONNX_COMMIT" ] ||
  die "v$SHERPA_ONNX_VERSION does not point at $SHERPA_ONNX_COMMIT"

# Upstream's release workflow runs this before building. It stamps the commit
# and its date into version.cc; the runners it uses are set to UTC.
if ! (cd "$src" && TZ=UTC ./new-release.sh >"$work/new-release.log" 2>&1); then
  tail -n 40 "$work/new-release.log" >&2
  die "new-release.sh failed"
fi
grep -q "sha1 = \"${SHERPA_ONNX_COMMIT:0:8}\"" "$src/sherpa-onnx/csrc/version.cc" ||
  die "new-release.sh did not stamp the commit into version.cc"

# Upstream's CMake uses an archive in the source directory instead of
# downloading one, and checks its hash in both cases.
fetch_eigen "$downloads/eigen-$EIGEN_VERSION.tar.gz"
cp "$downloads/eigen-$EIGEN_VERSION.tar.gz" "$src/eigen-$EIGEN_VERSION.tar.gz"

# The build must not depend on where it runs. Two things would otherwise tie
# the library to this directory:
#
#   - Log messages in sherpa-onnx print __FILE__, so the absolute path of the
#     checkout would be stored in the library. The work directory and the NDK
#     are therefore mapped to fixed names.
#   - Upstream builds with ThinLTO, which derives identifiers for local
#     symbols from the path of each source file exactly as the compiler was
#     given it. CMake passes absolute paths, so the launcher hands the source
#     file over relative to the directory the compiler runs in.
#
# The compiler launcher hook is the one upstream's workflow uses for ccache,
# so the build scripts themselves stay untouched. The launcher expects the
# source file to be the last argument, which is how CMake writes compile
# commands; anything else is passed through unchanged.
launcher="$work/compiler-launcher"
cat >"$launcher" <<'SH'
#!/bin/sh
work="$SHERPA_ONNX_WORK_DIR"
up=
case "$PWD" in
"$work"/*)
  up="$(printf '%s' "${PWD#"$work"}" | sed 's|/[^/]*|../|g')"
  up="${up%/}"
  remaining=$#
  for arg; do
    shift
    remaining=$((remaining - 1))
    if [ "$remaining" -eq 0 ]; then
      case "$arg" in
      "$work"/*) arg="$up${arg#"$work"}" ;;
      esac
    fi
    set -- "$@" "$arg"
  done
  set -- "$@" "-ffile-prefix-map=$up=/build"
  ;;
esac
exec "$@" "-ffile-prefix-map=$work=/build" "-ffile-prefix-map=$ANDROID_NDK=/ndk"
SH
chmod +x "$launcher"

# Settings the upstream scripts read from the environment. Text-to-speech is
# the only one that differs from upstream's defaults; the others are set so
# that a value inherited from the caller cannot change the result.
unset SHERPA_ONNXRUNTIME_LIB_DIR SHERPA_ONNXRUNTIME_INCLUDE_DIR
export ANDROID_NDK="$ndk"
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF
export SHERPA_ONNX_ANDROID_PLATFORM=android-21
export SHERPA_ONNX_ONNXRUNTIME_ROOT="$work/onnxruntime"
export SHERPA_ONNX_WORK_DIR="$work"
export CMAKE_C_COMPILER_LAUNCHER="$launcher"
export CMAKE_CXX_COMPILER_LAUNCHER="$launcher"

repack_args=()
for target in "${TARGETS[@]}"; do
  read -r abi script build_dir <<<"$target"
  echo "==> building $abi"
  # The log is printed on failure: a temporary work directory is removed when
  # the script exits.
  if ! (cd "$src" && "./$script" >"$work/build-$abi.log" 2>&1); then
    tail -n 80 "$work/build-$abi.log" >&2
    die "$script failed"
  fi
  built="$src/$build_dir/install/lib/libsherpa-onnx-jni.so"
  [ -f "$built" ] || die "$script did not produce $built"
  verify_library "$abi" "$built" "$work/upstream-aar/jni/$abi/libsherpa-onnx-jni.so"
  compiled="$(compiled_dependencies "$src/$build_dir")" ||
    die "$abi: could not read what the library was compiled from"
  [ "$compiled" = "$COMPILED_DEPENDENCIES" ] ||
    die "$abi: the library is compiled from a different set of third-party sources: $compiled"
  # The ONNX Runtime library must be the very file the upstream AAR ships.
  cmp -s "$work/onnxruntime/jni/$abi/libonnxruntime.so" "$work/upstream-aar/jni/$abi/libonnxruntime.so" ||
    die "$abi: libonnxruntime.so differs from the one in the upstream AAR"
  repack_args+=("$abi" "$built" "$work/upstream-aar/jni/$abi/libonnxruntime.so")
done

# The new AAR holds every entry of the upstream AAR outside jni/, plus the
# rebuilt JNI library and ONNX Runtime for the ABIs built above. Entries are
# stored uncompressed, in name order, with a fixed timestamp, so the same
# inputs always give the same file.
# The AAR is assembled away from the output path and put in place only after
# it has been compared with the recorded build.
mkdir -p "$work/staged"
# Looked at once more: the output path could have been pointed at the staging file
# while the build ran.
if [ -e "$output" ] && [ "$output" -ef "$staged" ]; then
  die "the output path is the same file as this script's own staging file (a link to it): $output"
fi
python3 - "$aar" "$staged" "${repack_args[@]}" <<'PY'
import sys
import zipfile

upstream, output, libraries = sys.argv[1], sys.argv[2], sys.argv[3:]
entries = {}
with zipfile.ZipFile(upstream) as z:
    for name in z.namelist():
        if not name.endswith("/") and not name.startswith("jni/"):
            entries[name] = z.read(name)
for abi, jni, onnxruntime in zip(libraries[0::3], libraries[1::3], libraries[2::3]):
    for path in (jni, onnxruntime):
        with open(path, "rb") as f:
            entries[f"jni/{abi}/{path.rsplit('/', 1)[1]}"] = f.read()
with zipfile.ZipFile(output, "w") as z:
    for name in sorted(entries):
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.create_system = 3
        info.external_attr = 0o644 << 16
        z.writestr(info, entries[name])
PY

output_sha256="$(sha256_of "$staged")"
echo "sha256 $output_sha256  $(basename "$output") (as built)"
differing=""
for target in "${TARGETS[@]}"; do
  read -r abi _ build_dir <<<"$target"
  jni_sha256="$(sha256_of "$src/$build_dir/install/lib/libsherpa-onnx-jni.so")"
  echo "sha256 $jni_sha256  jni/$abi/libsherpa-onnx-jni.so"
  for expected in "${EXPECTED_JNI_SHA256[@]}"; do
    read -r expected_abi expected_sha256 <<<"$expected"
    if [ "$expected_abi" = "$abi" ] && [ "$expected_sha256" != "$jni_sha256" ]; then
      differing="$differing jni/$abi/libsherpa-onnx-jni.so"
    fi
  done
done

# The AAR is put in place by renaming a copy made next to it: the name is replaced
# whole, so a link at the output path is replaced rather than written through.
place() {
  cp "$staged" "$output.tmp.$$"
  # A directory that appeared at the output path since the start would swallow the
  # rename (mv moves into it), and the "==> <output>" line below would be untrue.
  [ ! -d "$output" ] || die "the output path is a directory: $output"
  mv -f -- "$output.tmp.$$" "$output"
}

if [ "$output_sha256" = "$EXPECTED_AAR_SHA256" ]; then
  place
  echo "==> $output"
  echo "==> the AAR matches the recorded build ($EXPECTED_AAR_SHA256)"
elif [ "${REQUIRE_EXPECTED_AAR:-}" = "1" ]; then
  # Nothing is written. What was at the output path was dealt with before the
  # build started: it is the recorded build, or it is gone. Looked at once more all
  # the same — if the path shares its file with something this run wrote to (a link
  # into the work directory), it no longer holds the recorded build, and must go.
  previous=""
  if [ -e "$output" ] || [ -L "$output" ]; then
    if [ -f "$output" ] && [ "$(sha256_of "$output")" = "$EXPECTED_AAR_SHA256" ]; then
      previous=" The file already at that path is the recorded build and was left in place."
    elif [ -d "$output" ]; then
      previous=" A directory has appeared at that path; it was left alone."
    else
      rm -f -- "$output"
      previous=" The file that was at that path was not the recorded build and was removed."
    fi
  fi
  die "the AAR differs from the recorded build (expected $EXPECTED_AAR_SHA256; differing:${differing:- none of the compiled libraries}). Nothing was written to $output.$previous"
else
  place
  echo "==> $output"
  echo "==> the AAR differs from the recorded build (expected $EXPECTED_AAR_SHA256)."
  echo "    Differing:${differing:- none of the compiled libraries}."
  echo "    The checks above show what this AAR does not contain. They do not show that it is"
  echo "    the recorded build: compare the hashes above with a build made elsewhere before"
  echo "    relying on it. REQUIRE_EXPECTED_AAR=1 turns this difference into a failure."
fi
