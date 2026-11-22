#!/bin/zsh
# One-time setup: copies your release keystore into ./keystore/ and saves its password in
# ./keystore.properties so scripts/sign-release.sh runs without prompting.
# Both are git-ignored. Never commit them: the repo is public, and anyone holding the key
# can sign APKs as this app.
set -e
cd "${0:A:h}/.."
SRC="${1:-$HOME/keystores/tv-recents-release.jks}"
DEST=keystore/tv-recents-release.jks
[ -f "$SRC" ] || { echo "No keystore at $SRC (pass its path as the first argument)"; exit 1; }

read -s "KS_PASS?Keystore password: "
echo
export KS_PASS
keytool -list -keystore "$SRC" -storepass:env KS_PASS >/dev/null 2>&1 \
  || { echo "That password doesn't open $SRC"; exit 1; }

mkdir -p keystore
cp "$SRC" "$DEST"
chmod 600 "$DEST"
umask 077
printf 'KS_PASS=%q\n' "$KS_PASS" > keystore.properties
unset KS_PASS
echo "Saved $DEST and keystore.properties (both git-ignored)."
