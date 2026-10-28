# Hush Hour for Android (Slowpage)

Gate A spike: the Rust mixer from `hushhour-ipad` (inkpad-ipados, pinned revision) driven by an Oboe callback. No UI yet. Plan and decisions: `audit/handoffs/`.

## Build (macOS)
Needs NDK 29.0.14206865, SDK platform 36, CMake 4.1.2, JDK 17, `rustup target add aarch64-linux-android`, `cargo install cargo-ndk`.
```
scripts/build-rust.sh                 # .so, Kotlin bindings, music -> rust-out/
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```
On this Mac `~/.gradle` and `~/Library/Android/sdk` point at an external volume; use `GRADLE_USER_HOME=~/Android/gradle-home` and `sdk.dir=~/Android/sdk` in `local.properties`.

## Licence
MIT. Bundled music: CC0 (see the Hush Hour Linux repository).
