<#
.SYNOPSIS
    通过 Wi-Fi（ADB）把儿童桌面的 APK 装到电视上，不用 U 盘。

.DESCRIPTION
    做法参照 CleanYourTV：先在局域网里连上电视的 ADB，再推包、安装、验收，每一步都能看见结果。
    电视上需要先打开「ADB 调试 / 网络调试」，并且和这台电脑连同一个 WiFi。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\tv-install.ps1 -TvIp 192.168.10.66

.EXAMPLE
    # 装完顺手把悬浮窗权限给掉（省得在电视上手动点）
    powershell -ExecutionPolicy Bypass -File tools\tv-install.ps1 -TvIp 192.168.10.66 -GrantOverlay

.EXAMPLE
    # 一条命令上强锁（电视不能登录任何账号，否则 dpm 会拒绝）
    powershell -ExecutionPolicy Bypass -File tools\tv-install.ps1 -TvIp 192.168.10.66 -GrantOverlay -SetOwner

.EXAMPLE
    # 跨品牌一条龙：装包 + 悬浮窗 + 看门狗（无障碍） + 汇报电视型号和系统桌面包名
    powershell -ExecutionPolicy Bypass -File tools\tv-install.ps1 -TvIp 192.168.10.66 -GrantOverlay -EnableWatchdog
#>
[CmdletBinding()]
param(
    [string]$TvIp = "",
    [int]$Port = 5555,
    [string]$Apk = "",
    [switch]$GrantOverlay,
    [switch]$SetOwner,
    [switch]$SetHome,
    [switch]$Uninstall,
    # 开看门狗（无障碍服务）—— 锁桌面的关键一步，跨品牌尽量自动完成
    [switch]$EnableWatchdog,
    # 停用电视自带的系统桌面（激进：HOME 只能回儿童桌面，但崩了就回不去）
    [switch]$DisableLauncher,
    [int]$WaitSeconds = 120,
    # Android 11+ 的「无线调试」要先用配对码配对，再从它显示的端口连
    [string]$PairCode = "",
    [int]$PairPort = 0,
    [switch]$Find
)

$ErrorActionPreference = "Continue"
$PackageName = "com.kidstv.kiosk"

function Write-Step($text) {
    Write-Host ""
    Write-Host ("== " + $text) -ForegroundColor Cyan
    Write-Host ""
}

function Write-Ok($text) { Write-Host "   OK  $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "   !   $text" -ForegroundColor Yellow }
function Write-Bad($text) { Write-Host "   X   $text" -ForegroundColor Red }

# ---------- 1. 找 adb ----------

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $here            # KioskShell\
$workspaceRoot = Split-Path -Parent $projectRoot   # 工作区根目录

$candidates = New-Object System.Collections.ArrayList
[void]$candidates.Add((Join-Path $workspaceRoot ".toolchain\android-sdk\platform-tools\adb.exe"))
if ($env:ANDROID_HOME) { [void]$candidates.Add((Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")) }
if ($env:ANDROID_SDK_ROOT) { [void]$candidates.Add((Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")) }

$adb = $null
foreach ($c in $candidates) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Bad "没找到 adb。装一个 Android SDK platform-tools，或把它加进 PATH 再来。"
    exit 1
}
Write-Host "adb: $adb" -ForegroundColor DarkGray

# ---------- 2. 找 APK ----------

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $found = Get-ChildItem -Path $workspaceRoot -Filter "KioskShell-v*-debug.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $Apk = $found.FullName }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Bad "没找到 APK。用 -Apk 指定路径，例如 -Apk .\KioskShell-v1.3-debug.apk"
    exit 1
}
$apkItem = Get-Item $Apk
Write-Host ("apk: " + $apkItem.FullName + "  (" + [math]::Round($apkItem.Length/1MB,1) + " MB)") -ForegroundColor DarkGray

# ---------- 3. 连电视 ----------

if ([string]::IsNullOrWhiteSpace($TvIp) -and -not $Find) {
    Write-Step "还差电视的 IP"
    Write-Host "  两个办法拿："
    Write-Host "    a) 电视上：设置 → 网络与连接 → 有线/无线网络 → 点开当前网络看 IPv4 地址"
    Write-Host "    b) 本机跑：python tools\find-tv.py  （电视开了 ADB 才会扫得到）"
    Write-Host ""
    Write-Host "  然后：powershell -ExecutionPolicy Bypass -File tools\tv-install.ps1 -TvIp 192.168.x.x"
    exit 1
}

$serial = "{0}:{1}" -f $TvIp, $Port

if ($Find) {
    Write-Step "adb mDNS 发现（电视会自己广播，开没开调试看这里最准）"
    $mdns = (& { $ErrorActionPreference = "SilentlyContinue"; & $adb mdns services 2>&1 }) | Out-String
    if ([string]::IsNullOrWhiteSpace($mdns)) { $mdns = "   （没发现任何设备）" }
    Write-Host $mdns
    Write-Host "   带 _adb-tls-connect 的是「无线调试」的端口，带 _adb._tcp 的是传统 5555。"
    exit 0
}

Write-Step "连接 $serial"
& $adb start-server 2>&1 | Out-Null

if (-not [string]::IsNullOrWhiteSpace($PairCode)) {
    if ($PairPort -le 0) {
        Write-Bad "配对要填电视上「无线调试」页面显示的那个配对端口（六位数），用 -PairPort 传进来"
        exit 1
    }
    Write-Step "配对（Android 11+ 的「无线调试」需要先配对）"
    $pairTarget = "{0}:{1}" -f $TvIp, $PairPort
    $pairOut = & $adb pair $pairTarget $PairCode 2>&1 | Out-String
    Write-Host ("   " + $pairOut.Trim())
    if ($pairOut -match "Successfully paired") {
        Write-Ok "配对成功"
    } else {
        Write-Warn2 "配对没成功。配对码是一次性的，过期就回电视上重新生成；配对端口和连接端口不是同一个。"
    }
}

$connectOut = & $adb connect $serial 2>&1 | Out-String
Write-Host ("   " + $connectOut.Trim()) -ForegroundColor DarkGray

Write-Step "等电视授权（第一次会弹「是否允许 USB 调试」，用遥控器选「允许/确定」）"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$ready = $false
$toldUnauthorized = $false
while ((Get-Date) -lt $deadline) {
    $devices = & $adb devices 2>&1
    $line = $devices | Where-Object { $_ -match [regex]::Escape($serial) } | Select-Object -First 1
    if ($line) {
        if ($line -match "unauthorized") {
            if (-not $toldUnauthorized) {
                Write-Warn2 "电视上出现了授权弹窗，去按「允许」。注意勾上「一律允许」就不用每次点。"
                $toldUnauthorized = $true
            }
        }
        elseif ($line -match "\sdevice\s*$") {
            $ready = $true
            break
        }
        elseif ($line -match "offline") {
            Write-Warn2 "设备是 offline，等一下…"
        }
    }
    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Bad "连不上或没授权。按顺序查："
    Write-Host "   1. 只开了「USB 调试」但没开「网络调试」—— 有些固件必须两个都开，adbd 才会监听 5555"
    Write-Host "   2. 电视上开的是「无线调试」（Android 11+）—— 它的端口不是 5555，而且要先配对："
    Write-Host "      先加 -Find 看 adb 广播出来的端口，然后："
    Write-Host "      -TvIp 192.168.x.x -Port 37xxx -PairPort 40xxx -PairCode 123456"
    Write-Host "   3. 电视和电脑不在同一个网段（访客 WiFi / 双频分离）"
    Write-Host "   4. 电视重启后 ADB 自动关了 —— 重新开一次"
    Write-Host "   5. IP 变了 —— 重新跑 python tools\find-tv.py"
    exit 1
}
Write-Ok "已连接"

# ---------- 4. 机型信息 + 系统桌面探测 ----------

Write-Step "电视信息"
$model = (& $adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
$brand = (& $adb -s $serial shell getprop ro.product.brand 2>&1 | Out-String).Trim()
$mfr   = (& $adb -s $serial shell getprop ro.product.manufacturer 2>&1 | Out-String).Trim()
$sdk   = (& $adb -s $serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
$hasDeviceAdmin = (& $adb -s $serial shell pm list features 2>&1 | Out-String)
Write-Host ("   {0} {1}  (Android SDK {2})" -f $brand, $model, $sdk) -ForegroundColor DarkGray
if ($hasDeviceAdmin -match "android.software.device_admin") {
    Write-Ok "带 Device Admin 特性 —— 可以试 -SetOwner 强锁"
} else {
    Write-Host "   !   没有 android.software.device_admin 特性，Device Owner / Lock Task 用不了，走看门狗" -ForegroundColor Yellow
}

# 系统自带桌面的包名：不写死品牌，运行时问系统"按 HOME 会落到谁"
$launcherPkg = ""
$resolveOut = (& $adb -s $serial shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | Out-String)
if ($resolveOut -match "(?<pkg>(com|cn|org|net|tv)\.[A-Za-z0-9_.]+)/(?<act>[A-Za-z0-9_.$]+)") {
    $launcherPkg = $matches["pkg"]
}
if ($launcherPkg -and $launcherPkg -ne $PackageName) {
    Write-Host ("   系统桌面：{0}（按 HOME 默认回这里）" -f $launcherPkg) -ForegroundColor DarkGray
} else {
    Write-Warn2 "没探测到系统桌面包名，品牌差异大时可手动查：adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME"
}

# 按厂商给一句实话（脚本本身不写死任何包名，这里只是提醒）
switch -Regex ($brand + " " + $mfr) {
    "^(?i).*(mi|xiaomi|redmi).*" {
        Write-Host "   小米/MIUI 提示：`"网络调试`"要单独开；MIUI 会拦 settings put secure，脚本会自动走 pm grant 路径" -ForegroundColor DarkGray
    }
    "^(?i).*hisense.*" {
        Write-Host "   海信提示：要同时开 `"USB 调试`"和`"网络调试`"，adbd 才监听 5555" -ForegroundColor DarkGray
    }
    "^(?i).*(sony|bravia).*" {
        Write-Host "   索尼提示：Android TV 原生固件最配合，看门狗基本一次到位" -ForegroundColor DarkGray
    }
    "^(?i).*(tcl|ffalcon|雷鸟).*" {
        Write-Host "   TCL/雷鸟提示：部分机型 `"无线调试`"要先配对再连，用 -Find 看端口" -ForegroundColor DarkGray
    }
}

# ---------- 5. 装 ----------

if ($Uninstall) {
    Write-Step "卸载 $PackageName"
    $out = & $adb -s $serial shell pm uninstall --user 0 $PackageName 2>&1 | Out-String
    Write-Host ("   " + $out.Trim())
    exit 0
}

Write-Step "安装（覆盖安装，家长密码和已有设置都会保留）"
$installOut = & $adb -s $serial install -r -g $apkItem.FullName 2>&1 | Out-String
Write-Host ("   " + $installOut.Trim())
if ($installOut -notmatch "Success") {
    Write-Bad "安装失败。"
    if ($installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        Write-Host "   签名不一致：先卸载旧版再装。加 -Uninstall 跑一次，然后再装。"
    }
    if ($installOut -match "INSTALL_FAILED_DEVICE_OWNER") {
        Write-Host "   这台电视上本应用已经是设备所有者，普通卸载卸不掉。要在电视的家长菜单里撤销。"
    }
    exit 1
}
Write-Ok "安装成功"

# ---------- 6. 验收 ----------

Write-Step "验收"
$pkgLine = (& $adb -s $serial shell pm list packages $PackageName 2>&1 | Out-String).Trim()
if ($pkgLine -match [regex]::Escape($PackageName)) {
    Write-Ok "包已存在：$pkgLine"
} else {
    Write-Warn2 "没查到这个包，可能装到了别的用户下"
}
$ver = (& $adb -s $serial shell dumpsys package $PackageName 2>&1 | Select-String -Pattern "versionName" | Select-Object -First 1)
if ($ver) { Write-Host ("   " + $ver.ToString().Trim()) -ForegroundColor DarkGray }

# ---------- 7. 可选：把手工要点的地方用命令做掉 ----------

if ($GrantOverlay) {
    Write-Step "授予悬浮窗权限（到点自动盖出休息页要用）"
    $out = & $adb -s $serial shell cmd appops set $PackageName SYSTEM_ALERT_WINDOW allow 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($out)) { Write-Host ("   " + $out.Trim()) -ForegroundColor DarkGray }
    # 必须回读：Android 11 上 `appops set` 会静默不生效，只有 `cmd appops set` 才动
    $check = (& $adb -s $serial shell cmd appops get $PackageName SYSTEM_ALERT_WINDOW 2>&1 | Out-String).Trim()
    Write-Host ("   " + $check) -ForegroundColor DarkGray
    if ($check -match "allow") {
        Write-Ok "已允许 SYSTEM_ALERT_WINDOW"
    } else {
        Write-Warn2 "没设上。到电视上手动开：家长菜单 → 授予悬浮窗权限"
    }
}

if ($SetHome) {
    Write-Step "把它设成默认桌面（等价于家长菜单里的「锁定桌面」）"
    & $adb -s $serial shell pm enable "$PackageName/.HomeAlias" 2>&1 | Out-Null
    $out = & $adb -s $serial shell cmd package set-home-activity "$PackageName/.HomeAlias" 2>&1 | Out-String
    if ([string]::IsNullOrWhiteSpace($out) -or $out -match "Success") {
        Write-Ok "已设为默认桌面"
    } else {
        Write-Warn2 ("返回：" + $out.Trim() + "   可以改在电视上手动选")
    }
}

if ($SetOwner) {
    Write-Step "设置 Device Owner（强锁）"
    $out = & $adb -s $serial shell dpm set-device-owner "$PackageName/.KioskAdmin" 2>&1 | Out-String
    Write-Host ("   " + $out.Trim())
    if ($out -match "Success") {
        Write-Ok "已是 Device Owner。重启本应用会自动进入强锁。"
    } else {
        Write-Warn2 "没成功。最常见原因：电视登录了账号（海信账号/Google 账号），先在设置里删掉账号再试。"
    }
}

# ---------- 7.5 看门狗（无障碍）—— 跨品牌锁桌面的关键 ----------

if ($EnableWatchdog) {
    Write-Step "开启看门狗（无障碍服务）"
    $svc = "$PackageName/.KioskWatchService"

    # ① 标准路径：shell 直接写 secure settings，多数原生 Android TV 固件一次成功
    & $adb -s $serial shell settings put secure enabled_accessibility_services $svc 2>&1 | Out-Null
    & $adb -s $serial shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
    $check = (& $adb -s $serial shell settings get secure enabled_accessibility_services 2>&1 | Out-String).Trim()
    if ($check -match [regex]::Escape($PackageName)) {
        Write-Ok "无障碍已开启（看门狗生效）"
    } else {
        # ② MIUI / 部分定制固件拦了 settings put：先给自己发 WRITE_SECURE_SETTINGS 再写一次
        Write-Warn2 "标准路径没写上，试 WRITE_SECURE_SETTINGS 路径（小米/MIUI 常见）"
        & $adb -s $serial shell pm grant $PackageName android.permission.WRITE_SECURE_SETTINGS 2>&1 | Out-Null
        & $adb -s $serial shell settings put secure enabled_accessibility_services $svc 2>&1 | Out-Null
        & $adb -s $serial shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
        $check2 = (& $adb -s $serial shell settings get secure enabled_accessibility_services 2>&1 | Out-String).Trim()
        if ($check2 -match [regex]::Escape($PackageName)) {
            Write-Ok "无障碍已开启（WRITE_SECURE_SETTINGS 路径）"
        } else {
            Write-Warn2 "还是没写上。只能电视上手动开：设置 → 无障碍 → 儿童桌面（打开后 10 分钟内不会被弹回来）"
        }
    }

    # 顺手验证一下服务真的活着（崩了/被系统杀掉这里看得见）
    $a11y = (& $adb -s $serial shell dumpsys accessibility 2>&1 | Out-String)
    if ($a11y -match "Crashed services") {
        Write-Warn2 "dumpsys 里有 Crashed services —— 重启一次电视或重开本应用即可"
    }
}

if ($DisableLauncher) {
    if ([string]::IsNullOrWhiteSpace($launcherPkg) -or $launcherPkg -eq $PackageName) {
        Write-Warn2 "没探测到系统桌面包名，不敢停用。先手动查包名，或改用看门狗（-EnableWatchdog）"
    } else {
        Write-Step "停用系统桌面 $launcherPkg（激进锁法）"
        $out = & $adb -s $serial shell pm disable-user --user 0 $launcherPkg 2>&1 | Out-String
        Write-Host ("   " + $out.Trim())
        Write-Warn2 "停用后如果儿童桌面崩了，按 HOME 会没反应。恢复命令：adb shell pm enable $launcherPkg"
    }
}

# ---------- 8. 收尾 ----------

Write-Step "完成"
Write-Host "   下一步：在电视上打开「儿童桌面」→ 长按菜单键 2 秒 → 输密码 → 选目标应用 → 锁桌面"
Write-Host ""
Write-Host "   注意：ADB 开着的时候，这个网段里任何一台电脑都能对电视装包、改设置。" -ForegroundColor Yellow
Write-Host "     装完请到电视上把「ADB 调试 / 网络调试」关掉；下次要远程装再打开。" -ForegroundColor Yellow
