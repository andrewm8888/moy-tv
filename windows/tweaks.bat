@echo off
chcp 65001 >nul
rem Необязательная настройка YaOS. Каждый блок спрашивает подтверждение. Вернуть всё: undo.bat
cd /d "%~dp0.."
call windows\common.bat || goto end

echo.
echo 1. Отключить Алису, Яндекс Музыку, Яндекс Браузер, рекламный ID, заставку, отчёты об ошибках.
echo    Голосовой поиск и кнопка микрофона перестанут работать.
set a=& set /p a=Отключить? (y/n): 
if /i "%a%"=="y" for %%p in (com.yandex.tv.alice com.yandex.tv.music com.yandex.browser.tv com.yandex.android.advid com.yandex.tv.daydream com.yandex.tv.bugreportsender) do (
  %A% shell am force-stop %%p
  %A% shell pm disable-user --user 0 %%p
)

echo.
echo 2. Отключить «Я.ТВ Домашнее приложение» (экран Яндекса с рекламой). Лаунчер должен быть уже установлен.
set a=& set /p a=Отключить? (y/n): 
if /i "%a%"=="y" (
  %A% shell am force-stop com.yandex.tv.home
  %A% shell pm disable-user --user 0 com.yandex.tv.home
  %A% shell cmd package set-home-activity %PKG%/.MainActivity
)

echo.
echo 3. Ускорить анимации интерфейса (0.5 вместо 1).
set a=& set /p a=Ускорить? (y/n): 
if /i "%a%"=="y" (
  %A% shell settings put global window_animation_scale 0.5
  %A% shell settings put global transition_animation_scale 0.5
  %A% shell settings put global animator_duration_scale 0.5
)

echo.
echo 4. Запретить кино-приложениям работать в фоне: Lampa, Prisma TV, RuStore, LostFilm New TV, Кинопоиск, SmartTube, VLC.
set a=& set /p a=Запретить? (y/n): 
if /i "%a%"=="y" for %%p in (top.rootu.lampa top.rootu.prisma ru.vk.store.tv com.kraat.lostfilmnewtv ru.kinopoisk.yandex.tv org.smarttube.stable org.videolan.vlc) do (
  %A% shell cmd appops set %%p RUN_ANY_IN_BACKGROUND ignore
  %A% shell cmd appops set %%p RUN_IN_BACKGROUND ignore
  %A% shell am force-stop %%p
)

echo.
echo 5. Очистить кэш приложений.
set a=& set /p a=Очистить? (y/n): 
if /i "%a%"=="y" %A% shell pm trim-caches 2000M

echo.
echo ГОТОВО. Перезагрузите ТВ: выключите из розетки на 30 секунд.
:end
pause
