#!/bin/sh
# Re-copy vendor/inkpad-ipados/ from a local checkout of the (private) iPad repository, at one
# commit. Only the maintainer needs this, to take a newer core or new assets; a normal build
# never runs it. The checkout is only read (git archive): nothing in it is changed.
# Usage: scripts/sync-vendored-core.sh [REV] [PATH-TO-inkpad-ipados]
# Afterwards: update the commit in vendor/inkpad-ipados/VENDORED.md, review the diff, rebuild.
set -eu

REV=${1:-873d4f4c74ea22a52658494f2697e86c3a146ddf}
SRC=${2:-$HOME/Dev/inkpad-ipados}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=$ROOT/vendor/inkpad-ipados
R=HushHour/HushHour/Resources

# What the Android build uses, and nothing iPad-only (no Swift, Xcode project, App Store
# metadata, xcframework script or C header).
PATHS="HushHourCore/Cargo.toml HushHourCore/Cargo.lock HushHourCore/src HushHourCore/assets/sounds
$R/Music $R/Fonts
$R/Licences/LICENSE-Slowpage.txt $R/Licences/LICENSE-hushhour-core.txt $R/Licences/LICENSE-sounds.md
$R/Licences/LICENSE-MPL-2.0.txt $R/Licences/LICENSES-rust.txt
$R/Assets.xcassets/Icon.imageset/Icon-light.png $R/Assets.xcassets/Icon.imageset/Icon-dark.png
$R/Assets.xcassets/AppIcon.appiconset/AppIcon-light-1024.png"

FULL=$(git -C "$SRC" rev-parse --verify "$REV^{commit}")
rm -rf "$DEST/HushHourCore" "$DEST/HushHour"
mkdir -p "$DEST"
# shellcheck disable=SC2086
git -C "$SRC" archive --format=tar "$FULL" -- $PATHS | tar -x -C "$DEST"
echo "vendored inkpad-ipados $FULL into vendor/inkpad-ipados"
(cd "$DEST" && find HushHourCore HushHour -type f | sort)
