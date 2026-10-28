#!/bin/sh
# Gate A evidence: a clean build of the .so, the bindings and the release APK, with the tool
# versions and every check that audit/gate-a/evidence.md relies on, in one log.
# Usage: scripts/gate-a-evidence.sh > audit/gate-a/build-evidence.log 2>&1
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
[ -d "${ANDROID_HOME:-/nonexistent}/ndk" ] || ANDROID_HOME=$HOME/Android/sdk
export ANDROID_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/Android/gradle-home}
BT=$ANDROID_HOME/build-tools/36.0.0
LLVM=$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin
APK=app/build/outputs/apk/release/app-release.apk
SO=rust-out/jniLibs/arm64-v8a/libhushhour_ipad.so

step() { printf '\n== %s\n' "$*"; }

step "date and commit"
date -u '+%Y-%m-%dT%H:%M:%SZ'
git rev-parse HEAD
git status --short | sed 's/^/dirty: /'

step "tools"
rustc --version
cargo --version
cargo ndk --version
java -version 2>&1 | head -1
cat "$ANDROID_HOME/ndk/29.0.14206865/source.properties" | grep Pkg.Revision
"$BT/aapt2" version 2>&1
./gradlew --version -q | grep -E '^(Gradle|Kotlin|JVM)'
df -h / | tail -1

step "clean"
rm -rf rust-out app/build app/.cxx
if [ -d build/inkpad-ipados/HushHourCore ]; then
    (cd build/inkpad-ipados/HushHourCore && cargo clean -q)
fi

step "scripts/build-rust.sh"
scripts/build-rust.sh

step "so: file, dynamic section, exports, alignment"
shasum -a 256 "$SO"
file "$SO"
"$LLVM/llvm-readelf" -d "$SO" | grep -E 'SONAME|NEEDED'
"$LLVM/llvm-nm" -D --defined-only "$SO" | grep hushhour_mixer_render
echo "uniffi_ symbols: $("$LLVM/llvm-nm" -D --defined-only "$SO" | grep -c uniffi_)"
"$LLVM/llvm-readelf" -l "$SO" | grep LOAD

step "bindings"
find rust-out/kotlin -type f -exec shasum -a 256 {} \;
grep -c '' rust-out/kotlin/uniffi/hushhour/hushhour.kt

step "gradle clean assembleRelease"
./gradlew --console=plain clean :app:assembleRelease

step "apk: hash, manifest, contents, alignment, signature"
ls -l "$APK"
shasum -a 256 "$APK"
"$BT/aapt2" dump badging "$APK" | grep -E '^(package|sdkVersion|targetSdkVersion|supports-screens|uses-permission|application:)'
unzip -l "$APK" | grep -E '\.so$|\.ogg$'
"$BT/zipalign" -c -P 16 -v 4 "$APK" | tail -1
"$BT/apksigner" verify --print-certs "$APK" | head -2
TMP=$(mktemp -d)
unzip -q -o "$APK" 'lib/*' -d "$TMP"
"$LLVM/llvm-readelf" -d "$TMP/lib/arm64-v8a/libhushhour_audio.so" | grep NEEDED
"$LLVM/llvm-nm" -D -u "$TMP/lib/arm64-v8a/libhushhour_audio.so" | grep hushhour
rm -rf "$TMP"

step "runtime dependencies"
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E '^(\+|\\)---'
echo "play services / firebase: $(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -ciE 'gms|firebase|play-services' || true)"
df -h / | tail -1
