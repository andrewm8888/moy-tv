#!/bin/bash
# Установка лаунчера «Мой ТВ» на телевизор с YaOS.
# Запуск: bash scripts/install.sh            — скачать готовый APK из Releases
#         bash scripts/install.sh файл.apk   — поставить свой APK (например после build.sh)
source "$(dirname "$0")/common.sh"
connect_tv
APK="$1"
if [ -z "$APK" ]; then
  APK="$TOOLS/moy-tv.apk"; mkdir -p "$TOOLS"
  echo "Скачиваю лаунчер…"
  curl -L --fail -o "$APK" "$APK_URL" || { echo "Не скачалось: $APK_URL"; exit 1; }
fi
echo "=== Установка ==="
# Заглушка: на время установки гасим подсветку, чтобы на экране не мелькало фоновое приложение
BL=$($A shell settings get global picture_backlight | tr -d '\r'); case "$BL" in ''|null|0) BL=100;; esac
$A shell settings put global picture_backlight 0
trap '$A shell settings put global picture_backlight $BL' EXIT
$A install -r "$APK" || { echo "Установка не удалась. Если раньше стояла версия с другой подписью: $ADB -s $IP:5555 uninstall $PKG"; exit 1; }
echo "=== Права ==="
$A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
$A shell pm grant $PKG android.permission.DUMP
$A shell pm grant $PKG android.permission.PACKAGE_USAGE_STATS
$A shell appops set $PKG GET_USAGE_STATS allow
echo "=== Кнопка HOME ==="
$A shell cmd package set-home-activity $PKG/.MainActivity
echo "=== Служба кнопок и пульта с телефона ==="
SVC="$PKG/.KeyService"
CUR=$($A shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"; elif echo "$CUR" | grep -q "$SVC"; then NEW="$CUR"; else NEW="$CUR:$SVC"; fi
$A shell settings put secure enabled_accessibility_services "$NEW"
$A shell settings put secure accessibility_enabled 1
$A shell am start -n $PKG/.MainActivity >/dev/null
sleep 3; $A shell settings put global picture_backlight $BL
echo
echo "ГОТОВО. Нажмите HOME на пульте."
echo "Город для погоды: ОК на карточке погоды → «Сменить город»."
echo "Пульт с телефона: при первом нажатии ТВ спросит разрешение на отладку, выберите «Всегда»."
