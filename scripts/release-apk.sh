#!/bin/sh
# Build the sideloaded release APK from a committed tree, so the APK is attributable to HEAD
# (Codex phase 3 MINOR 6): refuses a dirty tree, checks the revision AGP embeds in the APK,
# checks the 30 MiB file card limit, prints the SHA-256 and the signer's certificate.
# Signing (phase 7): with the release keystore in ~/.android-keys (scripts/make-release-key.sh)
# its password is read from the macOS Keychain; otherwise the debug key signs, and the output
# says so. --release refuses to build without the release key.
set -eu

KEYSTORE=${SLOWPAGE_KEYSTORE:-$HOME/.android-keys/slowpage-release.jks}
SERVICE=slowpage-release-keystore
REQUIRE_RELEASE=no
[ "${1:-}" = "--release" ] && REQUIRE_RELEASE=yes
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

if [ -n "$(git status --porcelain)" ]; then
    echo "release-apk: the tree has uncommitted changes; commit first" >&2
    exit 1
fi
HEAD=$(git rev-parse HEAD)

[ -d "${ANDROID_HOME:-/nonexistent}/ndk" ] || ANDROID_HOME=$HOME/Android/sdk
export ANDROID_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/Android/gradle-home}
if [ -f "$KEYSTORE" ]; then
    SLOWPAGE_KEYSTORE=$KEYSTORE
    SLOWPAGE_KEYSTORE_PASSWORD=${SLOWPAGE_KEYSTORE_PASSWORD:-$(security find-generic-password -s "$SERVICE" -w)}
    export SLOWPAGE_KEYSTORE SLOWPAGE_KEYSTORE_PASSWORD
elif [ "$REQUIRE_RELEASE" = yes ]; then
    echo "release-apk: no release keystore at $KEYSTORE (scripts/make-release-key.sh)" >&2
    exit 1
fi
./gradlew -q :app:assembleRelease < /dev/null

APK=app/build/outputs/apk/release/app-release.apk
EMBEDDED=$(unzip -p "$APK" META-INF/version-control-info.textproto | sed -n 's/^ *revision: "\(.*\)"/\1/p')
if [ "$EMBEDDED" != "$HEAD" ]; then
    echo "release-apk: the APK records $EMBEDDED, HEAD is $HEAD" >&2
    exit 1
fi
BYTES=$(stat -f %z "$APK")
LIMIT=$((30 * 1024 * 1024))
if [ "$BYTES" -ge "$LIMIT" ]; then
    echo "release-apk: $BYTES bytes, over the 30 MiB file card limit" >&2
    exit 1
fi
echo "commit  $HEAD"
echo "size    $BYTES bytes"
echo "sha256  $(shasum -a 256 "$APK" | cut -d' ' -f1)"
APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
SIGNER=$("$APKSIGNER" verify --print-certs "$APK" | sed -nE 's/^Signer #1 certificate (DN|SHA-256 digest): //p')
echo "signer  $(echo "$SIGNER" | head -1)"
echo "cert    $(echo "$SIGNER" | tail -1)"
if echo "$SIGNER" | grep -q "CN=Android Debug"; then
    echo "release-apk: signed with the DEBUG key (no release keystore)" >&2
    [ "$REQUIRE_RELEASE" = no ] || exit 1
fi
