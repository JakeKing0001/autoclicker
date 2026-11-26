@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "PATH=%~dp0platform-tools;%PATH%"
where py >nul 2>nul && (set "PY=py") || (set "PY=python")
echo PROVA: il bot guarda e scrive cosa farebbe, ma NON tocca niente. Ctrl+C per fermare.
%PY% -m yarnloop_bot run --dry-run %*
pause
