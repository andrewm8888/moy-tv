#!/bin/bash
# Общая часть скриптов: adb и подключение к телевизору.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
PKG=ru.mikulinskiy.tvhome
APK_URL="https://github.com/andrewm8888/moy-tv/releases/latest/download/moy-tv.apk"

get_adb() {
  if command -v adb >/dev/null 2>&1; then ADB=adb; return; fi
  ADB="$TOOLS/platform-tools/adb"
  [ -x "$ADB" ] && return
  case "$(uname -s)" in
    Darwin) OS=darwin ;;
    Linux) OS=linux ;;
    *) echo "Неизвестная система. Поставьте adb вручную."; exit 1 ;;
  esac
  echo "Скачиваю adb от Google (~15 МБ)…"
  mkdir -p "$TOOLS"
  curl -L --fail -o "$TOOLS/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip" || { echo "Не скачалось. Проверьте интернет."; exit 1; }
  unzip -q -o "$TOOLS/pt.zip" -d "$TOOLS" && rm "$TOOLS/pt.zip"
}

connect_tv() {
  get_adb
  IP=$(cat "$ROOT/.tv_ip" 2>/dev/null)
  if [ -z "$IP" ]; then
    read -p "IP телевизора (например 192.168.1.50): " IP
    echo "$IP" > "$ROOT/.tv_ip"
  fi
  "$ADB" connect "$IP:5555" >/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if "$ADB" devices | grep -q "$IP:5555.*device$"; then break; fi
    [ $i = 1 ] && echo "Если на ТВ появился запрос «Разрешить отладку?», поставьте «Всегда» и нажмите ОК…"
    sleep 3; "$ADB" connect "$IP:5555" >/dev/null
  done
  if ! "$ADB" devices | grep -q "$IP:5555.*device$"; then
    echo "НЕТ ПОДКЛЮЧЕНИЯ к $IP. Проверьте: ADB включён, ТВ и компьютер в одной сети, IP верный."
    echo "Чтобы ввести другой IP, удалите файл .tv_ip в папке репозитория."
    "$ADB" devices; exit 1
  fi
  A="$ADB -s $IP:5555"
  echo "Телевизор $IP подключён."
}
