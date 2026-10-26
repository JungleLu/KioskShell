@echo off
REM ============================================================
REM  Restore the optional "draw over other apps" permission.
REM  This is only a Windows double-click shortcut for:
REM      python tools\kiosk.py overlay [tv-ip]
REM  macOS / Linux users: run that command directly in Terminal.
REM ============================================================
cd /d "%~dp0.."

py -3 tools\kiosk.py overlay %*
if not errorlevel 9009 goto done

python tools\kiosk.py overlay %*
if not errorlevel 9009 goto done

echo.
echo   Python 3 not found.
echo   Install it from https://www.python.org/downloads/  (tick "Add to PATH")
echo.
echo   Or do it with adb directly:
echo     adb shell cmd appops set com.kidstv.kiosk SYSTEM_ALERT_WINDOW allow

:done
if "%~1"=="" pause
