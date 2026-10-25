#!/bin/bash
# Поставить любой APK на телевизор: bash scripts/add-apk.sh путь/к/файлу.apk
source "$(dirname "$0")/common.sh"
[ -f "$1" ] || { echo "Укажите APK: bash scripts/add-apk.sh ~/Downloads/файл.apk"; exit 1; }
connect_tv
$A install -r "$1" && echo "ГОТОВО"
