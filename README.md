# Slowpage for Android

Slowpage is a quiet, distraction-free Markdown editor for tablets. This is the Android port. The same app exists for Linux ([HushHour](https://github.com/50hoursaweek/hushhour)) and iPad (Slowpage for iPadOS), and all three share one Rust core, `hushhour-core`.

- The page fills the screen.
- A spotlight dims everything but the paragraph or sentence you are writing.
- The caret line can be anchored mid-screen.
- There are three fonts (mono, sans, serif), three themes, and a split or full preview.
- Typing sounds, music loops and soundscapes keep you company, and a slow background pattern can move behind the page.

Free and open source under the MIT licence. There are no ads, no accounts and no network access: the app requests no Internet permission.

## Install

Slowpage is not on Google Play; it is published as an APK on this repository's Releases page.

Requirements:
- An Android **tablet** running Android 8.0 (API 26) or newer, with a 64-bit ARM processor (arm64-v8a). The app is built for tablets only.
- About 50 MB of free space (28 MB installed, plus 17 MB of music loops once one is played).

Steps:
1. Download `slowpage-1.0.0.apk` from Releases.
2. Optional: check it (see Verify below).
3. Open it on the tablet. Android asks to allow installs from that source (the browser or Files app): allow it in the prompt, or in Settings → Apps → Special app access → Install unknown apps.
4. Install, then open Slowpage. A short welcome tour shows on first launch.

### Verify the APK
Two checks prove the file is the one released here and that it is signed with Slowpage's release key.

1. Compare the APK's SHA-256 with the one listed in the release notes. For 1.0.0, built from commit `2a0f233a3453c80acf9abeed110d93c5ab4425ac`, it is:
   ```
   21ec497ff9c510b34184b1988fbdf7ad5dbfb14af61400a448c665861d2776fa
   ```
   To compute it:
   ```
   shasum -a 256 slowpage-1.0.0.apk        # macOS / Linux
   certutil -hashfile slowpage-1.0.0.apk SHA256   # Windows
   ```
2. Check the signing certificate (`apksigner` comes with the Android SDK build tools):
   ```
   apksigner verify --print-certs slowpage-1.0.0.apk
   ```
   The signer must be `CN=Slowpage, O=Grunt`, and the SHA-256 digest must be:
   ```
   b1b8e468db4216c5dd4bd981014fa32a908ad413c63e6bdad5d348fec1203136
   ```
   The same fingerprint, written with colons:
   `B1:B8:E4:68:DB:42:16:C5:DD:4B:D9:81:01:4F:A3:2A:90:8A:D4:13:C6:3E:6B:DA:D5:D3:48:FE:C1:20:31:36`

Every Slowpage release is signed with this one certificate, and Android installs an update only if its signature matches.

### Update
Install the newer APK over the installed one. Your documents and settings stay.

If you tested a pre-release build (signed with a development key), Android refuses to install a release over it. Export your documents first, then uninstall the test build, then install the release. Uninstalling deletes the app's documents, so exporting first is essential.

### Your documents
Documents are kept in the app's own storage on the tablet. They are not synced or backed up by Android (backup is turned off), so export anything you want to keep elsewhere:
- On the start screen, open a document's menu (⋯) and choose Export…, then pick a folder (Downloads, a USB drive, a cloud provider's folder). The document is saved in its own format (`.md`, or `.txt`).
- Or, in the editor, use Menu → Export….

Import… on the start screen brings Markdown and text files in.

## Build from source
Builds run on macOS with Apple silicon, which is where the app is built.

Toolchain:
| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform 36, build tools 36.0.0 |
| Android NDK | 29.0.14206865 |
| CMake (from the SDK) | 4.1.2 |
| Gradle (wrapper) | 9.8.2, Android Gradle Plugin 9.4.1, Kotlin 2.2.10 |
| Rust | 1.99 stable, with `rustup target add aarch64-linux-android` |
| cargo-ndk | 4.1.2 (`cargo install cargo-ndk`) |

Everything the build needs is in this repository, or public:
- **The iPad app's Rust crate (`hushhour-ipad`) and the bundled assets** are vendored in `vendor/inkpad-ipados/`, unmodified. The assets are the fonts, music loops, typing sounds, licence texts and icon art. `vendor/inkpad-ipados/VENDORED.md` lists the source commit, every file with its licence and checksum, and how they are re-synced.
- **`hushhour-core`** comes from the public [HushHour](https://github.com/50hoursaweek/hushhour) repository, at tag v0.4.2. Cargo fetches it, as `Cargo.lock` pins it.
- **Rust crates** come from crates.io.

The script builds:
- the core for arm64 Android and for the Mac (the JVM tests use the Mac build);
- the Kotlin bindings;
- the fonts, music, licence texts and icon, all into `rust-out/` (not committed).

```
# local.properties: sdk.dir=/path/to/Android/sdk
scripts/build-rust.sh                 # the core, bindings and assets -> rust-out/
./gradlew :app:testDebugUnitTest      # JVM tests (Robolectric), using rust-out/host
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
scripts/release-apk.sh                # a release APK from a clean, committed tree
```

### Signing
A release build is signed with the release key only when one is configured: `SLOWPAGE_KEYSTORE` (path) and `SLOWPAGE_KEYSTORE_PASSWORD`, or `slowpage.keystore` and `slowpage.keystorePassword` in `~/.gradle/gradle.properties`. Without them the build uses Android's debug key, so anyone can build and install their own copy. Such a copy cannot update, or be updated by, the official releases.

For the maintainer:
- `scripts/make-release-key.sh` creates the release key once, outside the repository, and keeps its password in the macOS Keychain.
- `scripts/release-apk.sh --release` refuses to build without that key. It prints the commit, size, SHA-256 and signing certificate.

No key, password or keystore is ever committed (`.gitignore` covers `*.jks`, `*.keystore` and `keystore.properties`).

## Repository layout
```
app/src/main/java/…   Kotlin: Compose UI, the editor (a TextView-based page), documents, audio control
app/src/main/cpp/     The Oboe audio stream that pulls the core's mixer
scripts/              build-rust.sh, release-apk.sh, make-release-key.sh, sync-vendored-core.sh
vendor/inkpad-ipados/ The iPad app's Rust crate and the bundled assets, vendored (VENDORED.md)
```

## Licence
MIT (`LICENSE`). Fonts are under the SIL Open Font License, typing sounds under CC0, and the libraries under Apache 2.0, MPL 2.0 and others. Everything is listed in `THIRD-PARTY-NOTICES.md` and in the app under About → Credits and licences.

Music loops generated with Google Lyria 3 (AI, SynthID watermark). Dedicated to the public domain under CC0, to the extent any rights exist.
