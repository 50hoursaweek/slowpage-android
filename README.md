# Slowpage for Android

Slowpage is a quiet, distraction-free Markdown editor for tablets. This is the Android port. The same app exists for Linux ([HushHour](https://github.com/50hoursaweek/hushhour)) and iPad ([Slowpage for iPadOS](https://github.com/50hoursaweek/inkpad-ipados)), and all three share one Rust core, `hushhour-core`.

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

1. Compare the APK's SHA-256 with the one listed in the release notes:
   ```
   shasum -a 256 slowpage-1.0.0.apk        # macOS / Linux
   certutil -hashfile slowpage-1.0.0.apk SHA256   # Windows
   ```
2. Check the signing certificate (`apksigner` comes with the Android SDK build tools):
   ```
   apksigner verify --print-certs slowpage-1.0.0.apk
   ```
   The SHA-256 digest must be:
   ```
   <RELEASE-CERT-SHA256>
   ```

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

The Rust core is not in this repository. `scripts/build-rust.sh` takes it from the iPad app's repository, [`inkpad-ipados`](https://github.com/50hoursaweek/inkpad-ipados), at the pinned revision `873d4f4`. It uses a local clone at `~/Dev/inkpad-ipados` if there is one, else GitHub; set `INKPAD_IPADOS_REPO` to another path or URL. That crate depends on [`hushhour-core`](https://github.com/50hoursaweek/hushhour) at tag v0.4.2, which Cargo fetches.

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
scripts/              build-rust.sh, release-apk.sh, make-release-key.sh
audit/                The project's QA trail: plans, handoffs, independent audits, device runs
```

## Licence
MIT (`LICENSE`). Fonts are under the SIL Open Font License, sounds and music under CC0, and the libraries under Apache 2.0, MPL 2.0 and others. Everything is listed in `THIRD-PARTY-NOTICES.md` and in the app under About → Credits and licences.
