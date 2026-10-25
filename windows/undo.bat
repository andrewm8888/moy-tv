@echo off
chcp 65001 >nul
rem Вернуть телевизор к заводскому виду
cd /d "%~dp0.."
call windows\common.bat || goto end
for %%p in (com.yandex.tv.alice com.yandex.tv.music com.yandex.browser.tv com.yandex.android.advid com.yandex.tv.daydream com.yandex.tv.bugreportsender com.yandex.tv.home com.yandex.tv.live com.yandex.tv.input.efir) do %A% shell pm enable %%p
for %%p in (top.rootu.lampa top.rootu.prisma ru.vk.store.tv com.kraat.lostfilmnewtv ru.kinopoisk.yandex.tv org.smarttube.stable org.videolan.vlc com.yandex.tv.home) do (
  %A% shell cmd appops set %%p RUN_ANY_IN_BACKGROUND allow
  %A% shell cmd appops set %%p RUN_IN_BACKGROUND allow
)
%A% shell settings put global window_animation_scale 1
%A% shell settings put global transition_animation_scale 1
%A% shell settings put global animator_duration_scale 1
%A% shell cmd package set-home-activity com.yandex.tv.home/.HomeActivity
set a=& set /p a=Удалить лаунчер «Мой ТВ»? (y/n): 
if /i "%a%"=="y" %A% uninstall %PKG%
echo ГОТОВО. Нажмите HOME на пульте.
:end
pause
