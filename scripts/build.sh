#!/bin/bash
# Сборка лаунчера из исходников без Android Studio и Gradle (Mac или Linux).
# Скачивает Java 17 и инструменты Android SDK в папку .tools (~500 МБ), результат: build/moy-tv.apk
# Установить собранное: bash scripts/install.sh build/moy-tv.apk
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="${MOYTV_TOOLS:-$ROOT/.tools}"; P="$ROOT/launcher"; O="$ROOT/build"; mkdir -p "$T" "$O"
case "$(uname -s)" in Darwin) OS=mac; SDKOS=mac ;; Linux) OS=linux; SDKOS=linux ;; *) echo "Только Mac или Linux"; exit 1 ;; esac
case "$(uname -m)" in arm64|aarch64) ARCH=aarch64 ;; *) ARCH=x64 ;; esac
for c in curl unzip zip; do command -v $c >/dev/null || { echo "Нужна программа $c"; exit 1; }; done

if [ ! -d "$T/jdk" ]; then
  echo "Скачиваю Java 17 (~190 МБ)…"
  curl -L --fail -o "$T/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  mkdir -p "$T/jdk" && tar -xzf "$T/jdk.tar.gz" -C "$T/jdk" && rm "$T/jdk.tar.gz"
fi
if [ "$OS" = mac ]; then JAVA_HOME="$(echo "$T"/jdk/*/Contents/Home)"; else JAVA_HOME="$(echo "$T"/jdk/*)"; fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

SDK="$T/sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Скачиваю Android command-line tools…"
  curl -L --fail -o "$T/clt.zip" "https://dl.google.com/android/repository/commandlinetools-$SDKOS-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools" && unzip -q -o "$T/clt.zip" -d "$SDK/cmdline-tools" && rm "$T/clt.zip"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -d "$SDK/build-tools/34.0.0" ] || [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo "Для сборки нужно принять лицензию Android SDK от Google."
  read -p "Принимаете? (д/н): " ok
  [ "$ok" = "д" ] || [ "$ok" = "y" ] || { echo "Отменено."; exit 1; }
  set +e; yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null; set -e
  "$SM" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0"
fi
BT="$SDK/build-tools/34.0.0"; AJ="$SDK/platforms/android-34/android.jar"

echo "=== Сборка ==="
W="$O/work"; rm -rf "$W"; mkdir -p "$W/gen" "$W/classes"
V=$(( $(date +%s) / 60 ))
"$BT/aapt2" compile --dir "$P/res" -o "$W/res.zip"
"$BT/aapt2" link -I "$AJ" --manifest "$P/AndroidManifest.xml" --java "$W/gen" \
  --min-sdk-version 28 --target-sdk-version 28 --version-code $V --version-name "1.$V" \
  -A "$P/assets" -o "$W/app.unaligned.apk" "$W/res.zip"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -d "$W/classes" \
  $(find "$P/src" "$W/gen" -name '*.java') 2>&1 | grep -v "^warning\|^Note\|bootstrap classpath\|source value 8\|target value 8\|To suppress" || true
[ -n "$(find "$W/classes" -name '*.class')" ] || { echo "ОШИБКА КОМПИЛЯЦИИ"; exit 1; }
"$BT/d8" --min-api 28 --lib "$AJ" --output "$W" $(find "$W/classes" -name '*.class')
(cd "$W" && zip -q -u app.unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$W/app.unaligned.apk" "$W/app.aligned.apk"
KS="${MOYTV_KEYSTORE:-$T/own.keystore}"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias tvhome \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MoyTV" >/dev/null
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$O/moy-tv.apk" "$W/app.aligned.apk"
echo "Собрано: build/moy-tv.apk"
echo "Своя подпись отличается от подписи APK из Releases: перед установкой поверх удалите старую версию."
