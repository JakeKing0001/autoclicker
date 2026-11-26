@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist logs mkdir logs
set "PATH=%~dp0platform-tools;%PATH%"
where py >nul 2>nul && (set "PY=py") || (set "PY=python")
echo Telefoni collegati (deve comparire una riga che finisce con "device"):
adb devices
echo.
echo Se c'e' scritto "unauthorized", sblocca il telefono e tocca "Consenti".
%PY% -m yarnloop_bot screenshot -o logs\prova.png
if exist logs\prova.png echo Screenshot di prova salvato in logs\prova.png
pause
