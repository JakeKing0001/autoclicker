@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist logs mkdir logs
set "PATH=%~dp0platform-tools;%PATH%"
where py >nul 2>nul && (set "PY=py") || (set "PY=python")
echo Il bot gioca da solo. Ctrl+C per fermarlo.
%PY% -m yarnloop_bot run %*
pause
