#!/bin/bash
# Вернуть телевизор к заводскому виду: включить всё отключённое, вернуть экран Яндекса, по желанию удалить лаунчер.
source "$(dirname "$0")/common.sh"
connect_tv
for p in com.yandex.tv.alice com.yandex.tv.music com.yandex.browser.tv com.yandex.android.advid com.yandex.tv.daydream com.yandex.tv.bugreportsender com.yandex.tv.home com.yandex.tv.live com.yandex.tv.input.efir; do
  $A shell pm enable $p >/dev/null 2>&1 && echo "включено: $p"
done
for p in top.rootu.lampa top.rootu.prisma ru.vk.store.tv com.kraat.lostfilmnewtv ru.kinopoisk.yandex.tv org.smarttube.stable org.videolan.vlc com.yandex.tv.home; do
  $A shell cmd appops set $p RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
  $A shell cmd appops set $p RUN_IN_BACKGROUND allow >/dev/null 2>&1
done
$A shell 'settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1'
$A shell cmd package set-home-activity com.yandex.tv.home/.HomeActivity
read -p "Удалить лаунчер «Мой ТВ»? (д/н): " a
if [ "$a" = "д" ] || [ "$a" = "y" ]; then $A uninstall $PKG; fi
echo "ГОТОВО. Нажмите HOME на пульте."
