@echo off
chcp 65001 >nul
rem Установка лаунчера «Мой ТВ». Можно перетащить свой APK на этот файл.
cd /d "%~dp0.."
call windows\common.bat || goto end
set APK=%~1
if "%APK%"=="" (
  set APK=%TOOLS%\moy-tv.apk
  echo Скачиваю лаунчер...
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%APK_URL%' -OutFile '%TOOLS%\moy-tv.apk'"
)
if not exist "%APK%" ( echo Не скачался APK: %APK_URL% & goto end )
echo === Установка ===
rem Заглушка: на время установки гасим подсветку
for /f %%b in ('%A% shell settings get global picture_backlight') do set BL=%%b
if "%BL%"=="" set BL=100
if "%BL%"=="null" set BL=100
if "%BL%"=="0" set BL=100
%A% shell settings put global picture_backlight 0
%A% install -r "%APK%" || ( %A% shell settings put global picture_backlight %BL% & echo Установка не удалась. Если стояла версия с другой подписью, удалите её: %A% uninstall %PKG% & goto end )
echo === Права ===
%A% shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
%A% shell pm grant %PKG% android.permission.DUMP
%A% shell pm grant %PKG% android.permission.PACKAGE_USAGE_STATS
%A% shell appops set %PKG% GET_USAGE_STATS allow
echo === Кнопка HOME ===
%A% shell cmd package set-home-activity %PKG%/.MainActivity
echo === Служба кнопок и пульта с телефона ===
%A% shell 'c=$(settings get secure enabled_accessibility_services); s=ru.mikulinskiy.tvhome/.KeyService; case x$c in *$s*) n=$c;; xnull) n=$s;; x) n=$s;; *) n=$c:$s;; esac; settings put secure enabled_accessibility_services $n; settings put secure accessibility_enabled 1'
%A% shell am start -n %PKG%/.MainActivity >nul
timeout /t 3 /nobreak >nul
%A% shell settings put global picture_backlight %BL%
echo.
echo ГОТОВО. Нажмите HOME на пульте.
echo Город для погоды: ОК на карточке погоды, затем «Сменить город».
:end
pause
