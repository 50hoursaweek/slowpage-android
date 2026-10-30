#!/bin/sh
# Gate A: build hushhour-ipad (inkpad-ipados/HushHourCore) for arm64-v8a and generate its
# Kotlin bindings. The crate is consumed at a pinned revision, never edited here.
# Output (not committed): rust-out/jniLibs/arm64-v8a/libhushhour_ipad.so, rust-out/kotlin/,
# rust-out/assets/music/, rust-out/assets/fonts/, rust-out/assets/licences/, rust-out/assets/about/, rust-out/host/libhushhour_ipad.dylib (for JVM unit tests).
set -eu

REV=873d4f4c74ea22a52658494f2697e86c3a146ddf   # inkpad-ipados: "add cdylib crate type"
SRC=${INKPAD_IPADOS_REPO:-$HOME/Dev/inkpad-ipados}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/build/inkpad-ipados
OUT=$ROOT/rust-out

# ~/Library/Android/sdk may point at an unmounted volume; the toolchain lives in ~/Android/sdk.
[ -d "${ANDROID_HOME:-/nonexistent}/ndk" ] || ANDROID_HOME=$HOME/Android/sdk
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export ANDROID_HOME ANDROID_NDK_HOME

if [ ! -d "$WORK/.git" ]; then
    git clone --quiet --no-checkout "$SRC" "$WORK"
fi
git -C "$WORK" fetch --quiet origin 2>/dev/null || true
git -C "$WORK" checkout --quiet --detach "$REV"
echo "hushhour-ipad at $(git -C "$WORK" rev-parse HEAD)"

cd "$WORK/HushHourCore"
# The soname lets the Android linker and JNA agree on one copy (one MIXER static).
# Panic messages carry source paths: map this Mac's folders to neutral ones (Codex phase 3 MINOR 8).
# The last matching prefix wins, so the general one comes first.
REMAP="--remap-path-prefix=$HOME=/home --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$WORK=/src"
RUSTFLAGS="${RUSTFLAGS:-} $REMAP -C link-arg=-Wl,-soname,libhushhour_ipad.so" \
    cargo ndk -t arm64-v8a -P 26 -o "$OUT/jniLibs" build --release --lib --locked

# The same crate for this Mac, so JVM unit tests can drive the core through the same
# Kotlin bindings (JNA loads it from rust-out/host).
cargo build --release --lib --locked
mkdir -p "$OUT/host"
cp target/release/libhushhour_ipad.dylib "$OUT/host/"

rm -rf "$OUT/kotlin"
cargo run --quiet --release --features cli --bin uniffi-bindgen -- \
    generate --library "$OUT/jniLibs/arm64-v8a/libhushhour_ipad.so" \
    --language kotlin --no-format --config "$ROOT/uniffi-android.toml" --out-dir "$OUT/kotlin"
# Bundled music: the CC0 loops of the Linux app, as shipped in the iPad bundle at $REV.
rm -rf "$OUT/assets/music"
mkdir -p "$OUT/assets/music"
cp "$WORK/HushHour/HushHour/Resources/Music/"*.ogg "$OUT/assets/music/"

# Editor fonts (F3): the three OFL families, unmodified, with their licences; the same files
# as hushhour-ref/assets/fonts (Linux), as shipped in the iPad bundle at $REV.
rm -rf "$OUT/assets/fonts"
mkdir -p "$OUT/assets/fonts"
for f in "$WORK/HushHour/HushHour/Resources/Fonts/"IBMPlex*.ttf "$WORK/HushHour/HushHour/Resources/Fonts/"Literata-*.ttf \
         "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-IBM-Plex-*.txt "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-Literata.txt \
         "$WORK/HushHour/HushHour/Resources/Fonts/"Lato-Regular.ttf "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-Lato.md; do
    cp "$f" "$OUT/assets/fonts/"
done

# Phase 6: About's licence texts and icon, as shipped in the iPad bundle at $REV (Lato above is
# the dialogs' face). The Android-only notices are in app/src/main/assets/licences.
rm -rf "$OUT/assets/licences" "$OUT/assets/about"
mkdir -p "$OUT/assets/licences" "$OUT/assets/about"
for f in LICENSE-Slowpage.txt LICENSE-hushhour-core.txt LICENSE-sounds.md LICENSE-MPL-2.0.txt LICENSES-rust.txt; do
    cp "$WORK/HushHour/HushHour/Resources/Licences/$f" "$OUT/assets/licences/"
done
cp "$WORK/HushHour/HushHour/Resources/Assets.xcassets/Icon.imageset/"Icon-*.png "$OUT/assets/about/"

find "$OUT" -type f | sed "s|$ROOT/||" | sort
