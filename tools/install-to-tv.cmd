@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  Install KioskShell to Android TV over Wi-Fi (no USB stick)
rem  Double-click this file, type the TV IP address.
rem ============================================================

cd /d "%~dp0"

echo.
echo === Install to TV over Wi-Fi ===
echo.
echo Before you start:
echo   1. TV is ON, same WiFi as this PC
echo   2. TV: enable Developer options + ADB / network debugging
echo   3. TV will ask "Allow USB debugging" - press Allow on remote
echo.
echo If you don't know the TV IP, run first:  python tools\find-tv.py
echo.

set /p TVIP=TV IP address: 
if "%TVIP%"=="" goto noip

echo.
echo   1  Install only
echo   2  Install + allow overlay permission (recommended)
echo   3  Install + overlay + watchdog (accessibility) - cross-brand one-stop
echo   4  Install + overlay + set Device Owner (strong lock, needs no account on TV)
echo.
set /p MODE=Choose 1/2/3/4 [2]:
if "%MODE%"=="" set MODE=2

if "%MODE%"=="1" goto mode1
if "%MODE%"=="3" goto mode3
if "%MODE%"=="4" goto mode4
goto mode2

:mode1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tv-install.ps1" -TvIp %TVIP%
goto end

:mode2
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tv-install.ps1" -TvIp %TVIP% -GrantOverlay
goto end

:mode3
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tv-install.ps1" -TvIp %TVIP% -GrantOverlay -EnableWatchdog
goto end

:mode4
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tv-install.ps1" -TvIp %TVIP% -GrantOverlay -SetOwner
goto end

:noip
echo No IP given, exit.

:end
echo.
pause
