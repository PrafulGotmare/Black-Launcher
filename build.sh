#!/usr/bin/env bash
# Builds a signed APK (install directly) and a signed AAB (upload to Google Play).
# Works on Ubuntu/Debian. Usage: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

API=36
KS=${KS:-keys/upload.keystore}
KS_ALIAS=${KS_ALIAS:-key}
KS_PASS=${KS_PASS:-blacklauncher}
CACHE=.cache; OUT=dist; WORK=.work
mkdir -p "$CACHE" "$OUT" keys

# Flat layout support (files uploaded straight from a phone): arrange them into the project structure
if [ ! -d app ]; then
  mkdir -p app/res/values app/res/xml app/res/drawable-xxhdpi app/src/main/java/com/minimal/blacklauncher
  cp AndroidManifest.xml app/
  cp themes.xml app/res/values/
  cp device_admin.xml app/res/xml/
  cp ic_launcher.png app/res/drawable-xxhdpi/
  cp MainActivity.java LockAdmin.java app/src/main/java/com/minimal/blacklauncher/
fi

# ---- tools ----
if ! command -v aapt2 >/dev/null || ! command -v javac >/dev/null || ! command -v apksigner >/dev/null || ! command -v dalvik-exchange >/dev/null; then
  SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
  $SUDO apt-get update -qq
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq openjdk-17-jdk-headless aapt apksigner zipalign dalvik-exchange unzip zip curl
fi
AJ="$CACHE/android-$API.jar"
[ -s "$AJ" ] || curl -fsSL -o "$AJ" "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$API/android.jar"
# aapt2 (Ubuntu build) can't read API 36 resource tables, so resources link against API 33
# (all attributes used here exist there) while Java compiles against API 36 and the manifest targets 36.
AJR="$CACHE/android-33.jar"
[ -s "$AJR" ] || curl -fsSL -o "$AJR" "https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar"
BT="$CACHE/bundletool.jar"
[ -s "$BT" ] || curl -fsSL -o "$BT" "https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar"

# ---- keystore (create once; KEEP IT SAFE, never commit it) ----
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
  -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BlackLauncher"

rm -rf "$WORK" "$OUT"/*; mkdir -p "$WORK/classes" "$WORK/compiled"

# ---- compile java -> dex ----
javac --release 8 -Xlint:-options -cp "$AJ" -d "$WORK/classes" $(find app/src -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$WORK/classes.dex" "$WORK/classes"

# ---- resources ----
aapt2 compile --dir app/res -o "$WORK/compiled/res.zip"
LINK="--manifest app/AndroidManifest.xml -I $AJR $WORK/compiled/res.zip --min-sdk-version 26 --target-sdk-version $API"

# ---- APK ----
aapt2 link -o "$WORK/app.unaligned.apk" $LINK
( cd "$WORK" && cp classes.dex classes.dex.tmp && aapt add app.unaligned.apk classes.dex >/dev/null )
zipalign -f 4 "$WORK/app.unaligned.apk" "$WORK/app.aligned.apk"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out "$OUT/BlackLauncher.apk" "$WORK/app.aligned.apk"
apksigner verify "$OUT/BlackLauncher.apk"

# ---- AAB (for Google Play) ----
aapt2 link --proto-format -o "$WORK/proto.apk" $LINK
mkdir -p "$WORK/base/manifest" "$WORK/base/dex"
( cd "$WORK/base" && unzip -q ../proto.apk )
mv "$WORK/base/AndroidManifest.xml" "$WORK/base/manifest/AndroidManifest.xml"
cp "$WORK/classes.dex" "$WORK/base/dex/classes.dex"
( cd "$WORK/base" && zip -qr ../base.zip . )
java -jar "$BT" build-bundle --modules="$WORK/base.zip" --output="$OUT/BlackLauncher.aab" --overwrite
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
  "$OUT/BlackLauncher.aab" "$KS_ALIAS" >/dev/null

rm -rf "$WORK"
echo "Done: $OUT/BlackLauncher.apk  and  $OUT/BlackLauncher.aab"
