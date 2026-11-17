#!/usr/bin/env bash
# End-to-end check of the in-process engine on the host JVM:
#   single APK  -> decompile -> layout matches cli/apkforge -> rebuild -> signature verifies
#   .apks split -> merge -> decompile (smali) -> rebuild
set -euo pipefail
cd "$(dirname "$0")/.."
ENGINE=engine/build/install/engine/bin/engine
APK=app/build/outputs/apk/debug/app-debug.apk
AAB=app/build/outputs/bundle/debug/app-debug.aab
OUT=build/smoke
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
rm -rf "$OUT"; mkdir -p "$OUT"

fail(){ echo "FAIL: $*" >&2; exit 1; }
check_layout(){ # project dir, expect jadx yes/no
  local p="$1"
  for f in input apktool info.txt decompile.log rebuild.sh .done; do [[ -e "$p/$f" ]] || fail "$p/$f missing"; done
  [[ "$2" == yes ]] && { [[ -d "$p/jadx" ]] || fail "$p/jadx missing"; }
  grep -q '^apktool: ok$' "$p/info.txt" || fail "apktool not ok: $(grep apktool: "$p/info.txt")"
  [[ -f "$p/apktool/AndroidManifest.xml" ]] || fail "no decoded manifest"
  local smali; smali=$(find "$p/apktool/smali" -name '*.smali' | wc -l)
  (( smali > 1000 )) || fail "only $smali smali files"
  echo "  layout ok: $(basename "$p") ($smali smali files)"
}

echo "== single APK"
time "$ENGINE" decompile "$APK" -o "$OUT"
P="$OUT/app_debug"
check_layout "$P" yes
grep -q '^jadx:    ok' "$P/info.txt" || fail "jadx: $(grep jadx: "$P/info.txt")"
java_files=$(find "$P/jadx" -name '*.java' | wc -l)
(( java_files > 1000 )) || fail "only $java_files java files"
[[ -f "$P/jadx/build.gradle" || -f "$P/jadx/app/build.gradle" ]] || fail "jadx gradle export missing"
grep -q "^package: name='com.gt.apkforge'" "$P/info.txt" || fail "badging missing"
cat "$P/info.txt"

echo "== rebuild"
"$ENGINE" rebuild "$P"
"$BT/apksigner" verify --min-sdk-version 26 "$P/dist/rebuilt-signed.apk"
"$BT/zipalign" -c -p 4 "$P/dist/rebuilt-signed.apk"
"$BT/aapt2" dump badging "$P/dist/rebuilt-signed.apk" | head -1

echo "== split bundle (.apks)"
if [[ -f "$AAB" ]]; then
  BTV=$(curl -fsSL https://dl.google.com/dl/android/maven2/com/android/tools/build/bundletool/maven-metadata.xml | grep -o '<release>[^<]*' | sed 's/<release>//')
  curl -fsSL -o "$OUT/bundletool.jar" "https://dl.google.com/dl/android/maven2/com/android/tools/build/bundletool/$BTV/bundletool-$BTV.jar"
  java -jar "$OUT/bundletool.jar" build-apks --bundle="$AAB" --output="$OUT/sample.apks"
  unzip -l "$OUT/sample.apks" | tail -n +4 | head -20
  "$ENGINE" decompile "$OUT/sample.apks" -o "$OUT" --smali-only
  S="$OUT/sample"
  check_layout "$S" no
  grep -q '^type:    bundle: [0-9]* splits merged' "$S/info.txt" || fail "split merge: $(grep type: "$S/info.txt")"
  [[ -f "$S/input/merged.apk" ]] || fail "no merged.apk"
  "$ENGINE" rebuild "$S"
  "$BT/apksigner" verify "$S/dist/rebuilt-signed.apk"
  cat "$S/info.txt"
else
  echo "  (no $AAB, skipped)"
fi
echo "SMOKE OK"
