@echo off
chcp 65001 >nul
rem Поставить любой APK: перетащите файл .apk на этот .bat
if "%~1"=="" ( echo Перетащите APK-файл на add-apk.bat & pause & exit /b 1 )
set FILE=%~1
cd /d "%~dp0.."
call windows\common.bat || goto end
%A% install -r "%FILE%" && echo ГОТОВО
:end
pause
