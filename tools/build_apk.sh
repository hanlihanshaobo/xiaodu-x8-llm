#!/usr/bin/env bash
# Linux/macOS 版构建脚本（GitHub Actions 用）。
# 依赖：JDK 8+、Android SDK（platforms;android-33 + build-tools;33.0.2）、zip
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/33.0.2"
AJ="$SDK/platforms/android-33/android.jar"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"

[ -d "$BT" ] || { echo "找不到 build-tools: $BT"; exit 1; }
[ -f "$AJ" ] || { echo "找不到 android.jar: $AJ"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$ROOT/client" "$WORK/client"
cd "$WORK/client"
mkdir -p build/gen build/classes build/dex

echo "1/6 编译资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "2/6 链接资源与清单"
"$BT/aapt2" link -o build/base.apk \
    -I "$AJ" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --java build/gen \
    --min-sdk-version 26 --target-sdk-version 28 \
    --auto-add-overlay

echo "3/6 编译 Java"
mapfile -t SOURCES < <(find src build/gen -name '*.java')
javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath "$AJ" \
    -d build/classes "${SOURCES[@]}"

echo "4/6 转 dex"
mapfile -t CLASSES < <(find build/classes -name '*.class')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 26 --output build/dex "${CLASSES[@]}"

echo "5/6 打包"
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk dex/classes.dex)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

echo "6/6 签名"
KS="${KEYSTORE:-$ROOT/tools/debug.keystore}"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

mkdir -p "$OUT_DIR"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_DIR/XiaoduLink.apk" build/aligned.apk

echo "完成: $OUT_DIR/XiaoduLink.apk"
ls -l "$OUT_DIR/XiaoduLink.apk"
