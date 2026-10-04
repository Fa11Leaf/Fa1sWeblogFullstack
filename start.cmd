@echo off
REM ---------------------------------------------------------------------------
REM  ASCII-only launcher for start.ps1
REM
REM  Why ASCII only: cmd.exe reads .bat/.cmd files with the OEM code page
REM  (936 on zh-CN Windows). If this file contained UTF-8 Chinese, it would be
REM  displayed as garbage. All Chinese text lives in start.ps1, which is saved
REM  as UTF-8 *with BOM* so that PowerShell 5.1 reads it correctly too.
REM
REM  Usage: double-click, or run from a terminal:  start.cmd -Check
REM ---------------------------------------------------------------------------
setlocal

set "SCRIPT=%~dp0start.ps1"
if not exist "%SCRIPT%" (
    echo [ERROR] start.ps1 not found: %SCRIPT%
    exit /b 1
)

where powershell >nul 2>nul
if errorlevel 1 (
    echo [ERROR] powershell.exe not found.
    echo         Windows PowerShell 5.1 or PowerShell 7+ is required.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
    echo.
    echo [ERROR] startup script exited with code %RC%.
    echo         Logs: %~dp0logs
)

REM Keep the window open when double-clicked (no arguments were passed).
if "%~1"=="" (
    echo.
    pause
)

exit /b %RC%
