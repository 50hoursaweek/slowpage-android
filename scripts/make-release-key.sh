#!/bin/sh
# Make the release signing key, once (phase 7). The keystore lives outside the repo, in
# ~/.android-keys/slowpage-release.jks (RSA 4096, 35 years, alias "slowpage"); its password is
# random and kept only in the macOS Keychain (service "slowpage-release-keystore"). Back the
# keystore AND the password up off this Mac: without both, no update can ever be signed for
# the people who installed Slowpage.
# Refuses to overwrite an existing keystore or Keychain entry.
set -eu

DIR=$HOME/.android-keys
KEYSTORE=$DIR/slowpage-release.jks
SERVICE=slowpage-release-keystore
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KEYTOOL=${KEYTOOL:-$JBR/bin/keytool}

if [ -e "$KEYSTORE" ]; then
    echo "make-release-key: $KEYSTORE exists; not touching it" >&2
    exit 1
fi
if security find-generic-password -s "$SERVICE" >/dev/null 2>&1; then
    echo "make-release-key: a Keychain entry \"$SERVICE\" exists; not touching it" >&2
    exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
SLOWPAGE_KEYSTORE_PASSWORD=$(openssl rand -base64 33 | tr -d '/+=\n')
export SLOWPAGE_KEYSTORE_PASSWORD
security add-generic-password -a slowpage -s "$SERVICE" -l "Slowpage Android release keystore" \
    -j "Password of $KEYSTORE (store and key, alias slowpage)" -w "$SLOWPAGE_KEYSTORE_PASSWORD"
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias slowpage \
    -keyalg RSA -keysize 4096 -validity 12784 -dname "CN=Slowpage, O=Grunt" \
    -storepass:env SLOWPAGE_KEYSTORE_PASSWORD -keypass:env SLOWPAGE_KEYSTORE_PASSWORD
chmod 600 "$KEYSTORE"

echo "keystore  $KEYSTORE"
"$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass:env SLOWPAGE_KEYSTORE_PASSWORD | grep -E "Owner|Valid|SHA256:"
