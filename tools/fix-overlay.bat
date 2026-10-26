@echo off
chcp 65001 >nul
setlocal

REM 恢复「儿童桌面」的悬浮窗权限。
REM 背景：VIDAA / 海信电视会周期性回收"在设置页手动授予"的悬浮窗权限，
REM       但通过 ADB 写入的不会被回收（实测 40+ 分钟稳定）。
REM
REM 用法：
REM   fix-overlay.bat                用默认 IP 192.168.10.81
REM   fix-overlay.bat 192.168.1.50   指定电视 IP

set "PKG=com.kidstv.kiosk"
set "TV_IP=%~1"
if "%TV_IP%"=="" set "TV_IP=192.168.10.81"

set "FALLBACK=D:\ProgramData\WorkBuddy\2026-10-21-13-41-57\.toolchain\android-sdk\platform-tools\adb.exe"

set "ADB="
where adb >nul 2>nul
if not errorlevel 1 set "ADB=adb"
if defined ADB goto haveadb
if exist "%FALLBACK%" set "ADB=%FALLBACK%"
if defined ADB goto haveadb

echo [x] 找不到 adb。请安装 Android platform-tools 并把 adb 加入 PATH，
echo     或修改本脚本里的 FALLBACK 路径。
pause
exit /b 1

:haveadb
echo [1/3] 连接电视 %TV_IP% ...
"%ADB%" connect %TV_IP%:5555

echo [2/3] 设置悬浮窗权限为 allow ...
"%ADB%" -s %TV_IP%:5555 shell cmd appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo [3/3] 验证 ...
"%ADB%" -s %TV_IP%:5555 shell cmd appops get %PKG% SYSTEM_ALERT_WINDOW

echo.
echo 完成。上面显示 "SYSTEM_ALERT_WINDOW: allow" 即成功。
pause
