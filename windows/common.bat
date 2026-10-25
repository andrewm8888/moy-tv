@rem Общая часть: adb и подключение к телевизору. Вызывается из других .bat
set PKG=ru.mikulinskiy.tvhome
set TOOLS=%CD%\.tools
set ADB=%TOOLS%\platform-tools\adb.exe
set APK_URL=https://github.com/andrewm8888/moy-tv/releases/latest/download/moy-tv.apk
if not exist "%ADB%" (
  echo Скачиваю adb от Google, около 15 МБ...
  mkdir "%TOOLS%" 2>nul
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile '%TOOLS%\pt.zip'; Expand-Archive -Force '%TOOLS%\pt.zip' '%TOOLS%'; Remove-Item '%TOOLS%\pt.zip'"
)
if not exist "%ADB%" ( echo Не удалось скачать adb. Проверьте интернет. & exit /b 1 )
if exist .tv_ip ( set /p IP=<.tv_ip ) else ( set /p IP=IP телевизора, например 192.168.1.50: )
if not exist .tv_ip ( >.tv_ip echo %IP%)
set IP=%IP: =%
"%ADB%" connect %IP%:5555 >nul
echo Если на ТВ появился запрос «Разрешить отладку?», выберите «Всегда» и нажмите ОК.
set N=0
:wait
"%ADB%" -s %IP%:5555 get-state 2>nul | findstr /b "device" >nul && goto ok
set /a N+=1
if %N% geq 10 goto fail
timeout /t 3 /nobreak >nul
"%ADB%" connect %IP%:5555 >nul
goto wait
:fail
echo НЕТ ПОДКЛЮЧЕНИЯ к %IP%. Проверьте: ADB включён, ТВ и компьютер в одной сети, IP верный.
echo Чтобы ввести другой IP, удалите файл .tv_ip в папке репозитория.
exit /b 1
:ok
set A="%ADB%" -s %IP%:5555
echo Телевизор %IP% подключён.
exit /b 0
