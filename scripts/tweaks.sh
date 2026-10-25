#!/bin/bash
# Необязательная настройка YaOS: убрать лишнее и ускорить ТВ. Каждый блок спрашивает подтверждение.
# Вернуть всё: bash scripts/undo.sh
source "$(dirname "$0")/common.sh"
connect_tv
yes_no() { read -p "$1 (д/н): " a; [ "$a" = "д" ] || [ "$a" = "y" ] || [ "$a" = "Д" ]; }

echo; echo "1. Отключить: Алису, Яндекс Музыку, Яндекс Браузер, рекламный ID, заставку, отправку отчётов об ошибках."
echo "   Голосовой поиск и кнопка микрофона перестанут работать."
if yes_no "Отключить"; then
  for p in com.yandex.tv.alice com.yandex.tv.music com.yandex.browser.tv com.yandex.android.advid com.yandex.tv.daydream com.yandex.tv.bugreportsender; do
    $A shell pm list packages | grep -q "package:$p$" || continue
    $A shell am force-stop $p; $A shell pm disable-user --user 0 $p >/dev/null && echo "  отключено: $p"
  done
fi

echo; echo "2. Отключить «Я.ТВ Домашнее приложение» (экран Яндекса с рекламой и подборками)."
echo "   Лаунчер «Мой ТВ» должен быть уже установлен."
if yes_no "Отключить"; then
  if $A shell pm path $PKG | grep -q package:; then
    $A shell am force-stop com.yandex.tv.home; $A shell pm disable-user --user 0 com.yandex.tv.home >/dev/null && echo "  отключено: com.yandex.tv.home"
    $A shell cmd package set-home-activity $PKG/.MainActivity
  else echo "  Пропущено: лаунчер не установлен."; fi
fi

echo; echo "3. Ускорить анимации интерфейса (0.5 вместо 1)."
if yes_no "Ускорить"; then
  $A shell 'settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5'
  echo "  готово"
fi

echo; echo "4. Запретить кино-приложениям работать в фоне (не стартуют сами и не держат память)."
echo "   Lampa, Prisma TV, RuStore, LostFilm New TV, Кинопоиск, SmartTube, VLC — какие установлены."
if yes_no "Запретить"; then
  for p in top.rootu.lampa top.rootu.prisma ru.vk.store.tv com.kraat.lostfilmnewtv ru.kinopoisk.yandex.tv org.smarttube.stable org.videolan.vlc; do
    $A shell pm path $p 2>/dev/null | grep -q package: || continue
    $A shell cmd appops set $p RUN_ANY_IN_BACKGROUND ignore
    $A shell cmd appops set $p RUN_IN_BACKGROUND ignore
    $A shell am force-stop $p
    echo "  фон запрещён: $p"
  done
fi

echo; echo "5. Очистить кэш приложений."
if yes_no "Очистить"; then $A shell pm trim-caches 2000M; echo "  готово"; fi
echo; echo "ГОТОВО. Перезагрузите ТВ (выключите из розетки на 30 секунд)."
