#!/bin/bash
# Build the Squeeze APK without Gradle: aapt2 + javac + d8 + apksigner.
#
# Requirements: JDK 17, Android SDK with platform android-34 and build-tools 34.0.0.
#   export ANDROID_SDK_ROOT=/path/to/android-sdk   (or ANDROID_HOME)
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
AJAR=$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
if [ -z "$BT" ] || [ -z "$AJAR" ]; then
  echo "Android SDK not found. Set ANDROID_SDK_ROOT (needs platforms;android-34 and build-tools;34.0.0)." >&2
  exit 1
fi
echo "build-tools: $BT"
echo "android.jar: $AJAR"

VCODE="${VERSION_CODE:-1}"
VNAME="${VERSION_NAME:-1.0}"

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o build/app-unsigned.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  build/res.zip \
  -A assets \
  --java build/gen \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VNAME" \
  --no-version-vectors

echo "[3/6] javac"
javac --release 11 -nowarn -cp "$AJAR" -d build/classes \
  $(find java -name '*.java') \
  build/gen/com/squeeze/compressor/R.java

echo "[4/6] d8"
"$BT/d8" --release --min-api 29 --lib "$AJAR" --output build/dex \
  $(find build/classes -name '*.class')

echo "[5/6] package + align"
(cd build/dex && zip -q -X ../app-unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "[6/6] sign"
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -alias squeeze \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass squeeze123 -keypass squeeze123 \
    -dname "CN=Squeeze, OU=Mobile, O=Squeeze, L=Pune, S=Maharashtra, C=IN"
fi
"$BT/apksigner" sign --ks release.keystore --ks-pass pass:squeeze123 \
  --key-pass pass:squeeze123 --min-sdk-version 29 \
  --out build/Squeeze.apk build/app-aligned.apk

"$BT/apksigner" verify --print-certs build/Squeeze.apk | head -4
ls -lh build/Squeeze.apk
