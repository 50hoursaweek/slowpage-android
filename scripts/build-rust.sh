#!/bin/sh
# Build hushhour-ipad (the iPad app's Rust crate, vendored in vendor/inkpad-ipados/HushHourCore)
# for arm64-v8a and generate its Kotlin bindings; copy the bundled assets next to them.
# Everything comes from this repository except hushhour-core, which Cargo fetches from the
# public HushHour repository at the tag in Cargo.lock. The vendored files are never edited here
# (vendor/inkpad-ipados/VENDORED.md; scripts/sync-vendored-core.sh re-copies them).
# Output (not committed): rust-out/jniLibs/arm64-v8a/libhushhour_ipad.so, rust-out/kotlin/,
# rust-out/assets/music/, rust-out/assets/fonts/, rust-out/assets/licences/, rust-out/assets/about/, rust-out/res/mipmap-*/ (launcher icon), rust-out/host/libhushhour_ipad.dylib (for JVM unit tests).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/vendor/inkpad-ipados
OUT=$ROOT/rust-out
# Cargo's build output stays out of the vendored folder.
CARGO_TARGET_DIR=$ROOT/build/cargo-target
export CARGO_TARGET_DIR

# ~/Library/Android/sdk may point at an unmounted volume; the toolchain lives in ~/Android/sdk.
[ -d "${ANDROID_HOME:-/nonexistent}/ndk" ] || ANDROID_HOME=$HOME/Android/sdk
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export ANDROID_HOME ANDROID_NDK_HOME

echo "hushhour-ipad: $(sed -n 's/^Source commit: `\(.*\)`/\1/p' "$WORK/VENDORED.md") (vendored)"

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
cp "$CARGO_TARGET_DIR/release/libhushhour_ipad.dylib" "$OUT/host/"

rm -rf "$OUT/kotlin"
cargo run --quiet --release --features cli --bin uniffi-bindgen -- \
    generate --library "$OUT/jniLibs/arm64-v8a/libhushhour_ipad.so" \
    --language kotlin --no-format --config "$ROOT/uniffi-android.toml" --out-dir "$OUT/kotlin"
# Bundled music: the CC0 loops of the Linux app, as shipped in the iPad bundle (vendored).
rm -rf "$OUT/assets/music"
mkdir -p "$OUT/assets/music"
cp "$WORK/HushHour/HushHour/Resources/Music/"*.ogg "$OUT/assets/music/"

# Editor fonts (F3): the three OFL families, unmodified, with their licences; the same files
# as hushhour-ref/assets/fonts (Linux), as shipped in the iPad bundle (vendored).
rm -rf "$OUT/assets/fonts"
mkdir -p "$OUT/assets/fonts"
for f in "$WORK/HushHour/HushHour/Resources/Fonts/"IBMPlex*.ttf "$WORK/HushHour/HushHour/Resources/Fonts/"Literata-*.ttf \
         "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-IBM-Plex-*.txt "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-Literata.txt \
         "$WORK/HushHour/HushHour/Resources/Fonts/"Lato-Regular.ttf "$WORK/HushHour/HushHour/Resources/Fonts/"LICENSE-Lato.md; do
    cp "$f" "$OUT/assets/fonts/"
done

# Phase 6: About's licence texts and icon, as shipped in the iPad bundle (vendored) (Lato above is
# the dialogs' face). The Android-only notices are in app/src/main/assets/licences.
rm -rf "$OUT/assets/licences" "$OUT/assets/about"
mkdir -p "$OUT/assets/licences" "$OUT/assets/about"
for f in LICENSE-Slowpage.txt LICENSE-hushhour-core.txt LICENSE-sounds.md LICENSE-MPL-2.0.txt LICENSES-rust.txt; do
    cp "$WORK/HushHour/HushHour/Resources/Licences/$f" "$OUT/assets/licences/"
done
cp "$WORK/HushHour/HushHour/Resources/Assets.xcassets/Icon.imageset/"Icon-*.png "$OUT/assets/about/"

# Phase 7: the launcher icon, the iPad's app icon (light), unchanged art. Below Android 8 the
# square as it is (48 dp); from 8 on the adaptive icon (app/src/main/res/mipmap-anydpi-v26)
# draws it at 66 dp in the middle of the 108 dp layer, padded with its own background colour
# (#F6EBD6), so the launcher's mask never cuts the strokes.
ICON="$WORK/HushHour/HushHour/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-light-1024.png"
rm -rf "$OUT/res"
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
    dir="$OUT/res/mipmap-${d%%:*}"
    scale=${d#*:}
    mkdir -p "$dir"
    legacy=$(echo "48 * $scale / 1" | bc)
    art=$(echo "66 * $scale / 1" | bc)
    layer=$(echo "108 * $scale / 1" | bc)
    sips -z "$legacy" "$legacy" "$ICON" --out "$dir/ic_launcher.png" >/dev/null
    sips -z "$art" "$art" "$ICON" --out "$dir/ic_launcher_foreground.png" >/dev/null
    sips -p "$layer" "$layer" --padColor F6EBD6 "$dir/ic_launcher_foreground.png" >/dev/null 2>&1
done

find "$OUT" -type f | sed "s|$ROOT/||" | sort
