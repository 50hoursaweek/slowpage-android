#!/bin/sh
# Build the sideloaded release APK from a committed tree, so the APK is attributable to HEAD
# (Codex phase 3 MINOR 6): refuses a dirty tree, checks the revision AGP embeds in the APK,
# checks the 30 MiB file card limit, prints the SHA-256.
set -eu
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
