#!/usr/bin/env python3
"""
儿童桌面（KioskShell）跨平台命令行工具。

Windows / macOS / Linux 同一套命令，只依赖 Python 3.7+ 和 adb。
不写死任何 IP、路径、品牌：电视地址自己输入或自动发现，adb 自动查找，
应用包名从 build.gradle.kts 读。

常用：
    python3 tools/kiosk.py discover             # 找电视，它会打印该敲的命令
    python3 tools/kiosk.py doctor               # 体检：能不能装、缺什么
    python3 tools/kiosk.py install 192.168.1.50 # 装 APK + 开无障碍（IP 换成你电视的）
    python3 tools/kiosk.py watchdog             # 只开无障碍（开机自启靠它）
    python3 tools/kiosk.py overlay              # 恢复悬浮窗权限（可选）
    python3 tools/kiosk.py recover-launcher     # 救命：重新启用被停用的系统桌面

不用 IP 也行：上面任一命令省略 target，就作用于当前唯一已连接的设备。
Windows 下把 python3 换成 python 即可。
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------- 基础

IS_WIN = os.name == "nt"
HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent

DEFAULT_PORT = 5555


def find_app_id() -> str:
    """从 build.gradle.kts 读 applicationId；读不到再退回默认值。"""
    for p in (PROJECT / "app/build.gradle.kts", PROJECT / "app/build.gradle"):
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        m = re.search(r'applicationId\s*=\s*["\']([^"\']+)["\']', text)
        if m:
            return m.group(1)
    return "com.kidstv.kiosk"


PKG = find_app_id()
WATCH_COMPONENT = f"{PKG}/.KioskWatchService"


def color(text: str, code: str) -> str:
    if not sys.stdout.isatty() or os.environ.get("NO_COLOR"):
        return text
    return f"\033[{code}m{text}\033[0m"


def step(msg: str) -> None:
    print("\n" + color("==> ", "36") + msg)


def ok(msg: str) -> None:
    print("    " + color("OK", "32") + "  " + msg)


def warn(msg: str) -> None:
    print("    " + color(" !", "33") + "  " + msg)


def bad(msg: str) -> None:
    print("    " + color(" X", "31") + "  " + msg)


def die(msg: str, code: int = 1):
    bad(msg)
    sys.exit(code)


# ---------------------------------------------------------------- adb 定位

def _adb_name() -> str:
    return "adb.exe" if IS_WIN else "adb"


def find_adb(explicit: str = "") -> str:
    """按优先级找 adb：参数 > 环境变量 > PATH > 项目内 > SDK 目录 > 常见安装位。"""
    cands = []
    if explicit:
        cands.append(explicit)
    for env in ("ADB", "ANDROID_ADB"):
        if os.environ.get(env):
            cands.append(os.environ[env])

    on_path = shutil.which("adb")
    if on_path:
        cands.append(on_path)

    # 老用户留着 tools/adb-path.txt 的，继续认
    try:
        recorded = (HERE / "adb-path.txt").read_text(encoding="utf-8").strip()
        if recorded:
            cands.append(recorded)
    except OSError:
        pass

    for p in (HERE / _adb_name(), HERE / "platform-tools" / _adb_name()):
        cands.append(str(p))

    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            cands.append(str(Path(root) / "platform-tools" / _adb_name()))

    home = Path.home()
    cands.append(str(home / "Library/Android/sdk/platform-tools/adb"))   # macOS
    cands.append(str(home / "Android/Sdk/platform-tools/adb"))           # Linux
    if IS_WIN:
        local = os.environ.get("LOCALAPPDATA")
        if local:
            cands.append(str(Path(local) / "Android/Sdk/platform-tools/adb.exe"))
        cands.append(str(home / "AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    for p in ("/opt/homebrew/bin/adb", "/usr/local/bin/adb", "/usr/bin/adb"):
        cands.append(p)

    for c in cands:
        if c and Path(c).expanduser().is_file():
            return str(Path(c).expanduser())
    die(
        "没找到 adb。\n"
        "  装法：macOS `brew install --cask android-platform-tools`；\n"
        "        Windows 装 Android SDK Platform-Tools 后把 platform-tools 目录加进 PATH；\n"
        "        或把 adb 可执行文件放到 tools/ 目录下。\n"
        "  也可以用 --adb /完整/路径/adb 指定。"
    )


class Device:
    def __init__(self, adb: str, serial: str = ""):
        self.adb = adb
        self.serial = serial

    def run(self, *args, timeout: int = 60, check: bool = False) -> str:
        cmd = [self.adb] + (["-s", self.serial] if self.serial else []) + list(args)
        try:
            r = subprocess.run(
                cmd, capture_output=True, text=True, timeout=timeout,
                encoding="utf-8", errors="replace",
            )
        except subprocess.TimeoutExpired:
            if check:
                die(f"命令超时：adb {' '.join(args)}")
            return ""
        except OSError as e:
            if check:
                die(f"adb 执行失败：{e}")
            return ""
        out = (r.stdout or "") + (r.stderr or "")
        if check and r.returncode != 0:
            die(f"adb {' '.join(args)} 失败：{out.strip()[:300]}")
        return out.strip()

    def sh(self, cmdline: str) -> str:
        """直接抛给电视的 shell 执行。"""
        return self.run("shell", cmdline)

    def prop(self, key: str) -> str:
        return self.sh(f"getprop {key}").strip()


def list_devices(adb: str):
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=30,
                         encoding="utf-8", errors="replace").stdout
    found = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            found.append((parts[0], parts[1]))
    return found


def connect(adb: str, target: str, wait: int = 45) -> str:
    """连接无线调试设备，等到授权完成。返回 serial。"""
    serial = target if ":" in target else f"{target}:{DEFAULT_PORT}"
    step(f"连接 {serial}")
    out = subprocess.run([adb, "connect", serial], capture_output=True, text=True,
                         timeout=40, encoding="utf-8", errors="replace")
    msg = ((out.stdout or "") + (out.stderr or "")).strip()
    if msg:
        print("    " + msg.splitlines()[0])

    deadline = time.time() + wait
    told = False
    while time.time() < deadline:
        for d, st in list_devices(adb):
            if d != serial:
                continue
            if st == "device":
                ok("已连接")
                return serial
            if st == "unauthorized" and not told:
                warn("电视上会弹「是否允许 USB 调试」，用遥控器选「允许 / 确定」")
                told = True
        time.sleep(2)

    bad("连不上。按顺序排查：")
    print("      1) 电视的「网络调试 / ADB 调试」开了吗（有的机型要同时开 USB 调试）")
    print("      2) 电脑和电视在同一个 Wi-Fi 吗")
    print("      3) 电视有没有弹授权框（弹了就用遥控器点允许）")
    print("      4) 换个口试试：--port 5555 或 adb tcpip")
    sys.exit(3)


# ---------------------------------------------------------------- 子命令

def _python_cmd() -> str:
    """按当前平台给出可直接粘贴的解释器名。"""
    return "python" if os.name == "nt" else "python3"


def cmd_discover(args, adb: str) -> None:
    py = _python_cmd()
    step("已连接的设备")
    devices = list_devices(adb)
    online = [d for d, st in devices if st == "device"]
    if devices:
        for d, st in devices:
            print(f"    {d:<28} {st}")
    else:
        print("    （没有）")

    step("网络里广播出来的调试设备（Android 11+，电视会自己广播）")
    out = subprocess.run([adb, "mdns", "services"], capture_output=True, text=True,
                         timeout=20, encoding="utf-8", errors="replace")
    text = (out.stdout or "") + (out.stderr or "")
    ips = re.findall(r"(\d+\.\d+\.\d+\.\d+):(\d+)", text)
    if ips:
        for ip, port in sorted(set(ips)):
            print(f"    {ip}:{port}")
    else:
        print("    （没发现。电视没开调试，或者不在同一网段）")

    step("下一步")
    if len(online) == 1:
        print("    已有一台连上了，下面这些命令可以不带 IP：")
        print(f"      {py} tools/kiosk.py doctor")
        print(f"      {py} tools/kiosk.py install")
    elif len(online) > 1:
        print("    连着多台，用 -s 指定一台（序列号见上）：")
        print(f"      {py} tools/kiosk.py doctor -s {online[0]}")
    elif ips:
        print("    拿上面任意一个 IP 去连（地址要换成实际的）：")
        print(f"      {py} tools/kiosk.py doctor {ips[0][0]}")
        print(f"      {py} tools/kiosk.py install {ips[0][0]}")
    else:
        print("    电视 IP 到 设置 → 网络 → 当前网络（或路由器后台）里看")


def cmd_doctor(args, dev: Device) -> None:
    brand = dev.prop("ro.product.brand") or dev.prop("ro.product.manufacturer")
    model = dev.prop("ro.product.model")
    release = dev.prop("ro.build.version.release")
    sdk = dev.prop("ro.build.version.sdk")
    step("这台电视")
    print(f"    品牌型号  {brand} {model}")
    print(f"    系统      Android {release}（API {sdk}）")

    features = dev.sh("pm list features")
    has_admin = "android.software.device_admin" in features
    step("能力")
    print(f"    Device Owner（强锁）  {'支持' if has_admin else '不支持 —— 只能用看门狗弱锁'}")

    launcher = ""
    resolved = dev.sh(
        "cmd package resolve-activity -a android.intent.action.MAIN "
        "-c android.intent.category.HOME"
    )
    m = re.search(r"packageName=(\S+)", resolved)
    if m:
        launcher = m.group(1)
    print(f"    系统桌面            {launcher or '探测不到'}")

    step("儿童桌面")
    installed = dev.sh(f"pm list packages {PKG}")
    if PKG not in installed:
        bad(f"还没装。下一步：{_python_cmd()} tools/kiosk.py install（设备已连上，不必再带 IP）")
        return
    ver = dev.sh(f"dumpsys package {PKG}")
    m = re.search(r"versionName=(\S+)", ver)
    code = re.search(r"versionCode=(\d+)", ver)
    ok(f"已安装 {m.group(1) if m else '?'}（versionCode {code.group(1) if code else '?'}）")

    acc = dev.sh("settings get secure enabled_accessibility_services")
    acc_on = dev.sh("settings get secure accessibility_enabled")
    watch_ok = (".KioskWatchService" in acc) and acc_on.strip() == "1"
    if watch_ok:
        ok("无障碍已开 —— 开机自启、HOME 拉回、到点锁屏都成立")
    else:
        bad("无障碍没开 —— 开机不自启、HOME 拉不回来")
        print("       修：python tools/kiosk.py watchdog")

    appops = dev.sh(f"cmd appops get {PKG} SYSTEM_ALERT_WINDOW")
    if "allow" in appops:
        ok("悬浮窗 allow（可选，能让锁屏页盖得更快）")
    else:
        warn("悬浮窗未授权（可选，不影响主要功能）")
        print("       想给：python tools/kiosk.py overlay")


def _latest_apk(explicit: str) -> Path:
    if explicit:
        p = Path(explicit).expanduser()
        if not p.is_file():
            die(f"找不到 APK：{p}")
        return p

    def ver_key(p: Path):
        nums = re.findall(r"\d+", p.name)
        return [int(n) for n in nums] or [0]

    for base in (PROJECT, PROJECT.parent, Path.cwd()):
        found = [p for p in base.glob("KioskShell-v*.apk") if p.is_file()]
        if found:
            return sorted(found, key=ver_key)[-1]
    app_apk = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
    if app_apk.is_file():
        return app_apk
    die("没找到 APK。用 --apk /路径/xxx.apk 指定，或先按 docs/build.md 编译。")


def cmd_install(args, dev: Device) -> None:
    apk = _latest_apk(args.apk)
    step(f"安装 {apk.name}")
    out = dev.run("install", "-r", "-g", str(apk), timeout=180)
    if "Success" in out:
        ok("安装成功（覆盖安装会保留家长密码和设置）")
    elif "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
        die("签名不一致：先卸载旧版再装（python tools/kiosk.py uninstall），然后重试。")
    else:
        die(f"安装失败：{out.strip()[:300]}")

    # 系统在升级/重装应用时会自动关掉它的无障碍服务，这里顺手补回来
    step("开启无障碍（开机自启、HOME 拉回都靠它）")
    enable_watchdog(dev)


def enable_watchdog(dev: Device) -> bool:
    acc = dev.sh("settings get secure enabled_accessibility_services")
    if ".KioskWatchService" in acc:
        dev.sh("settings put secure accessibility_enabled 1")
        ok("已经开着")
        return True

    current = acc.strip()
    if current in ("", "null", "None"):
        newval = WATCH_COMPONENT
    elif WATCH_COMPONENT in current:
        newval = current
    else:
        newval = current + ":" + WATCH_COMPONENT

    dev.sh(f'settings put secure enabled_accessibility_services "{newval}"')
    dev.sh("settings put secure accessibility_enabled 1")

    if ".KioskWatchService" in dev.sh("settings get secure enabled_accessibility_services"):
        ok("已开启")
        return True

    # 部分 MIUI / 定制固件会拦 settings put，需要先拿 WRITE_SECURE_SETTINGS
    warn("被固件拦了，改走授权路径重试")
    dev.run("shell", "pm", "grant", PKG, "android.permission.WRITE_SECURE_SETTINGS")
    dev.sh(f'settings put secure enabled_accessibility_services "{newval}"')
    dev.sh("settings put secure accessibility_enabled 1")
    if ".KioskWatchService" in dev.sh("settings get secure enabled_accessibility_services"):
        ok("已开启（走授权路径）")
        return True

    bad("还是没开上，请在电视上手动开一次：")
    print("      设置 → 无障碍（或「辅助功能」）→ 已下载的服务 → 儿童桌面 → 打开")
    return False


def cmd_watchdog(args, dev: Device) -> None:
    step("开启无障碍服务")
    enable_watchdog(dev)


def cmd_overlay(args, dev: Device) -> None:
    step("恢复悬浮窗权限（可选）")
    dev.sh(f"cmd appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    got = dev.sh(f"cmd appops get {PKG} SYSTEM_ALERT_WINDOW")
    if "allow" in got:
        ok("已设为 allow")
        warn("部分固件（实测海信/VIDAA）几分钟后会自己收回，掉了重跑这一条就行")
        return
    dev.sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    got = dev.sh(f"appops get {PKG} SYSTEM_ALERT_WINDOW")
    if "allow" in got:
        ok("已设为 allow")
    else:
        bad("没成功。可以在电视上：设置 → 应用 → 儿童桌面 → 显示在其他应用上层")


def find_stock_launcher(dev: Device) -> str:
    """探测系统自带桌面的包名，不猜品牌。"""
    resolved = dev.sh(
        "cmd package resolve-activity -a android.intent.action.MAIN "
        "-c android.intent.category.HOME"
    )
    m = re.search(r"packageName=(\S+)", resolved)
    if m and m.group(1) != PKG:
        return m.group(1)

    listing = dev.sh(
        "cmd package query-activities -a android.intent.action.MAIN "
        "-c android.intent.category.HOME"
    )
    pkgs = re.findall(r"packageName=(\S+)", listing)
    for p in dict.fromkeys(pkgs):
        if p != PKG:
            return p
    return ""


def cmd_recover_launcher(args, dev: Device) -> None:
    """救命用：之前把系统桌面停用了（激进锁法），电视没桌面时执行。"""
    step("重新启用系统桌面")
    target = args.launcher_pkg or find_stock_launcher(dev)
    if not target:
        die("探测不到系统桌面包名。用 --launcher-pkg 手动指定，"
            "例如 --launcher-pkg com.jamdeo.tv.vod")
    dev.sh(f"pm enable {target}")
    dev.sh(f"pm enable-user --user 0 {target}")
    out = dev.sh(f"pm list packages -e {target}")
    if target in out:
        ok(f"{target} 已启用")
        print("    按遥控器 HOME 键试试")
    else:
        bad(f"{target} 还是停用状态，可能要恢复出厂设置")


def cmd_uninstall(args, dev: Device) -> None:
    step("卸载儿童桌面")
    out = dev.run("uninstall", PKG)
    if "Success" in out:
        ok("已卸载（电视上的配置也一起清掉，重装是全新的）")
    elif "DEVICE_OWNER" in out.upper() or "not installed for" in out:
        bad("卸不掉：它可能还是 Device Owner。先在电视家长菜单里「撤销 Device Owner」")
    else:
        print("    " + out.strip()[:300])


# ---------------------------------------------------------------- 入口

def main() -> None:
    py = _python_cmd()
    p = argparse.ArgumentParser(
        prog="kiosk.py",
        description="儿童桌面（KioskShell）命令行工具 —— Windows / macOS / Linux 通用",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""192.168.1.50 只是示例，换成你电视的局域网 IP。
不知道电视 IP：先跑 discover，它会把该敲的命令直接打出来。
设备已经连上时，所有命令都可以省略 IP。

例子:
  {py} tools/kiosk.py discover
  {py} tools/kiosk.py doctor 192.168.1.50
  {py} tools/kiosk.py install 192.168.1.50
  {py} tools/kiosk.py watchdog
  {py} tools/kiosk.py recover-launcher
""",
    )
    p.add_argument("--adb", default="", help="adb 可执行文件路径（默认自动查找）")
    p.add_argument("-s", "--serial", default="", help="设备序列号，多设备时用")
    p.add_argument("--port", type=int, default=DEFAULT_PORT,
                   help=f"无线调试端口（默认 {DEFAULT_PORT}）")

    sub = p.add_subparsers(dest="cmd", metavar="命令")

    def add(name: str, help_text: str, with_target: bool = True):
        sp = sub.add_parser(name, help=help_text)
        if with_target:
            sp.add_argument("target", nargs="?", default="",
                            help="电视 IP，例如 192.168.1.50（换成你自己的）；"
                                 "不填则用当前唯一已连接的设备")
        # 子命令后面也认 -s（SUPPRESS：没传就不覆盖顶层已解析的值）
        sp.add_argument("-s", "--serial", default=argparse.SUPPRESS,
                        help="设备序列号，多设备时用")
        return sp

    add("discover", "找电视（已连接的 + 局域网里广播的）", with_target=False)
    add("doctor", "体检：系统版本、能不能强锁、无障碍开没开")
    pi = add("install", "安装 APK 并开启无障碍")
    pi.add_argument("--apk", default="", help="APK 路径（默认自动找最新的）")
    add("watchdog", "只开无障碍（开机自启靠它）")
    add("overlay", "恢复悬浮窗权限（可选）")
    pr = add("recover-launcher", "救命：重新启用被停用的系统桌面")
    pr.add_argument("--launcher-pkg", default="", help="系统桌面包名，不填就自动探测")
    add("uninstall", "卸载儿童桌面")

    args = p.parse_args()
    if not args.cmd:
        p.print_help()
        return

    adb = find_adb(args.adb)

    if args.cmd == "discover":
        cmd_discover(args, adb)
        return

    target = getattr(args, "target", "") or ""
    serial = args.serial
    if target:
        serial = connect(adb, target if ":" in target else f"{target}:{args.port}")
    elif not serial:
        online = [d for d, st in list_devices(adb) if st == "device"]
        if not online:
            die(
                "没有连上任何电视。\n"
                "  带上电视 IP 再跑（192.168.1.50 换成你电视的）：\n"
                f"    {py} tools/kiosk.py {args.cmd} 192.168.1.50\n"
                f"  不知道 IP：{py} tools/kiosk.py discover"
            )
        if len(online) > 1:
            print("检测到多台设备，用 -s 指定一台：")
            for d, st in list_devices(adb):
                print(f"  {d:<26} {st}")
            sys.exit(2)
        serial = online[0]

    dev = Device(adb, serial)
    handler = {
        "doctor": cmd_doctor,
        "install": cmd_install,
        "watchdog": cmd_watchdog,
        "overlay": cmd_overlay,
        "recover-launcher": cmd_recover_launcher,
        "uninstall": cmd_uninstall,
    }[args.cmd]
    handler(args, dev)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已取消")
        sys.exit(130)
