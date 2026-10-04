@echo off
REM ---------------------------------------------------------------------------
REM  ASCII-only launcher: stop everything start.ps1 started.
REM
REM  Stops the recorded PIDs (python / backend / admin) and the MySQL container.
REM  Data stays in the docker volume, nothing is deleted.
REM ---------------------------------------------------------------------------
setlocal

set "SCRIPT=%~dp0start.ps1"
if not exist "%SCRIPT%" (
    echo [ERROR] start.ps1 not found: %SCRIPT%
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Stop
set "RC=%ERRORLEVEL%"

echo.
pause
exit /b %RC%
