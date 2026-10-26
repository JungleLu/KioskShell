@echo off
chcp 936 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  恢复「儿童桌面」的悬浮窗权限（SYSTEM_ALERT_WINDOW）
REM
REM  为什么需要这个脚本：
REM    VIDAA / 海信电视会周期性回收"在设置页手动授予"的悬浮窗权限
REM    （实测 4-5 分钟一次，logcat 无任何记录），但通过 ADB 写入的
REM    appops 不会被回收（实测 40+ 分钟稳定）。
REM    所以正确做法不是反复去设置页点"允许"，而是用 ADB 直接写入。
REM
REM  前提：电视已开启 ADB 调试（设置 - 关于 - 连点版本号 - 开发者选项
REM        - 开启「网络调试 / USB 调试」），且电脑与电视在同一局域网。
REM
REM  用法：
REM    fix-overlay.bat                 自动找已连接的电视，或提示输入 IP
REM    fix-overlay.bat 192.168.1.50    指定电视 IP
REM ============================================================

set "PKG=com.kidstv.kiosk"

REM ---------- 1. 找 adb ----------
REM 查找顺序：ADB_PATH 环境变量 -> PATH -> tools\adb-path.txt（本机自用，已 gitignore）
REM           -> tools\adb.exe -> Android SDK 常见位置
set "ADB="

if defined ADB_PATH if exist "%ADB_PATH%" set "ADB=%ADB_PATH%"

if not defined ADB (
    where adb >nul 2>nul
    if not errorlevel 1 set "ADB=adb"
)

if not defined ADB if exist "%~dp0adb-path.txt" (
    set /p ADB_LINE=<"%~dp0adb-path.txt"
    if exist "!ADB_LINE!" set "ADB=!ADB_LINE!"
)

if not defined ADB if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"

if not defined ADB (
    echo [x] 找不到 adb。
    echo.
    echo     三种解法，任选一种：
    echo       [1] 安装 Android SDK Platform-Tools 并加入 PATH
    echo           https://developer.android.com/tools/releases/platform-tools
    echo       [2] 把 adb.exe 放到本脚本同目录：tools\adb.exe
    echo       [3] 在本脚本同目录建 adb-path.txt，里面写 adb.exe 的完整路径
    echo.
    pause
    exit /b 1
)

REM ---------- 2. 确定电视地址 ----------
set "TV_IP=%~1"
set "TARGET="

if defined TV_IP goto havedev

REM 没传参：先看有没有已经连上的设备
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if not defined TARGET if /i "%%b"=="device" set "TARGET=%%a"
)
if defined TARGET (
    echo [i] 检测到已连接的设备：!TARGET!
    goto checkadb
)

echo 请输入电视的 IP 地址（电视上：设置 - 网络 - 当前网络 - IPv4 地址）
set /p TV_IP=IP:
if not defined TV_IP (
    echo [x] 没输入 IP，退出。
    pause
    exit /b 1
)

:havedev
echo %TV_IP% | findstr ":" >nul
if errorlevel 1 (set "TARGET=%TV_IP%:5555") else (set "TARGET=%TV_IP%")

:checkadb
REM ---------- 3. 连接 + 授权 + 验证 ----------
echo.
echo [1/3] 连接电视 %TARGET% ...
"%ADB%" connect %TARGET%

echo [2/3] 把悬浮窗权限写成 allow ...
"%ADB%" -s %TARGET% shell cmd appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo [3/3] 验证 ...
echo.
"%ADB%" -s %TARGET% shell cmd appops get %PKG% SYSTEM_ALERT_WINDOW

echo.
echo --------------------------------------------
echo 上面出现 SYSTEM_ALERT_WINDOW: allow 就是成功了。
echo 这个授权不会被电视固件回收；重启后失效时再跑一次即可。
echo --------------------------------------------
pause
