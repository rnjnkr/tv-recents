#!/bin/zsh
# Builds the release APK and signs it with your own key.
# With keystore/tv-recents-release.jks and keystore.properties in place (see
# scripts/setup-signing.sh) it runs without prompting; otherwise it uses
# ~/keystores/tv-recents-release.jks, creates it on first run, and asks for the password.
# The password only reaches apksigner through the environment.
set -e
cd "${0:A:h}/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
if [ -f keystore/tv-recents-release.jks ]; then
  KS=keystore/tv-recents-release.jks
else
  KS="${KEYSTORE:-$HOME/keystores/tv-recents-release.jks}"
fi
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
OUT=app/build/outputs/apk/release
APK="$OUT/tv-recents-v$VERSION.apk"

if [ ! -f "$KS" ]; then
  echo "No keystore at $KS, creating one."
  echo "Back it up: without it, future versions can't be installed over this one."
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias tvrecents -keyalg RSA -keysize 4096 \
    -validity 10000 -dname "CN=Recents"
fi

./gradlew -q assembleRelease
"$BT/zipalign" -f -p 4 "$OUT/app-release-unsigned.apk" "$OUT/aligned.apk"
if [ -f keystore.properties ]; then
  . ./keystore.properties
else
  read -s "KS_PASS?Keystore password: "
  echo
fi
export KS_PASS
"$BT/apksigner" sign --ks "$KS" --ks-pass env:KS_PASS --out "$APK" "$OUT/aligned.apk"
unset KS_PASS
rm -f "$OUT/aligned.apk" "$APK.idsig"
"$BT/apksigner" verify --print-certs "$APK" | head -3
echo "Signed: $APK"
