# 儿童桌面 KioskShell

![platform](https://img.shields.io/badge/platform-Android%20TV%205.0%2B-3ddc84)
![license](https://img.shields.io/badge/license-MIT-blue)
![deps](https://img.shields.io/badge/permissions-none%20beyond%20accessibility-lightgrey)

**English** — Turn an Android TV or TV box into a single-app kiosk for kids: one big icon on screen, a parent PIN to get out, and an automatic break when the time is up. Works on any Android TV (5.0+); no brand is hard-coded.

把安卓电视变成「只能看一个 App」的儿童模式：桌面上只有一个大图标，退出要家长密码，到点自动弹「休息一下」。**不刷机、不 root、不联云。**

![儿童桌面首页](docs/images/home.jpg)

## 它解决什么

- 孩子只能打开你选的那个 App，按 HOME 也回不到别的应用
- 「每次看 10 分钟、休息 10 分钟、每天 90 分钟」这类规则，到点自动执行，不用靠嘴喊
- 家长要看片子：长按菜单键输密码，临时回原桌面，重启自动锁回来

## 30 秒上手

前提：**安卓**电视或盒子（Android 5.0+），电视和电脑在同一个 Wi-Fi，电视里打开「网络调试 / ADB 调试」。
只有「VIDAA 版本号」、找不到「Android 版本」的那种电视装不了（[怎么确认](docs/guide.md#先确认这台电视是不是安卓)）。

> 本文出现的 IP 都只是示例：写成 `192.168.1.50` 或 `<电视IP>`，**一律换成你电视的局域网 IP**，照抄连不上。
> 不知道 IP：先跑 `discover`，它会把你的地址和该敲的命令直接打出来；或在电视上 `设置 → 网络 → 当前网络` 里看。
> 设备一旦连上，后面的命令都可以省略 IP。

### 方式一：让 AI 帮你装（最省事）

把这句话发给 Claude Code / Cursor / WorkBuddy 之类的 AI 助手（把 IP 换成你电视的）：

> 读一下这个仓库的 `SKILL.md`，按它的步骤把我的电视装成儿童桌面，电视 IP 是 `192.168.1.50`

它会自己连接、体检、装包、开权限、验证，装完告诉你怎么用。

### 方式二：自己敲三条命令

| 做什么 | Windows | macOS / Linux |
|---|---|---|
| 找电视（不知道 IP 时用） | `python tools\kiosk.py discover` | `python3 tools/kiosk.py discover` |
| 体检（能不能装、缺什么） | `python tools\kiosk.py doctor <电视IP>` | `python3 tools/kiosk.py doctor <电视IP>` |
| 装 APK + 开无障碍 | `python tools\kiosk.py install <电视IP>` | `python3 tools/kiosk.py install <电视IP>` |

`<电视IP>` 指换成真实地址（例如 `192.168.1.50`，不要带尖括号）；电视已经连上时直接省略，命令会作用于当前唯一设备。

只需要 Python 3 和 adb，Windows / macOS / Linux 同一套命令。Windows 上 `python` 如果不是命令，换成 `py -3`。
没装 adb：macOS `brew install --cask android-platform-tools`；Windows 装 [Platform-Tools](https://developer.android.com/tools/releases/platform-tools) 后加进 PATH。

### 方式三：完全不想碰命令行

1. 把 `KioskShell-v*-debug.apk`（版本号以 Releases 最新版为准）拷到 U 盘，插电视安装
2. 电视上打开「儿童桌面」，**长按遥控器菜单键 2 秒**，输密码 `123456`
3. 家长菜单 →「开启儿童桌面」→ 按提示跳到无障碍设置，打开「儿童桌面」

第 3 步就是**开机自启**，很多人卡在这里。

## 电视上还要做三件事

| 做什么 | 在哪 | 说明 |
|---|---|---|
| **开无障碍** | 家长菜单 →「开启儿童桌面」→「去打开」 | **必做**。开机自启、HOME 拉回、到点锁屏全靠它 |
| **选应用** | 家长菜单 →「选择应用」 | 从已安装列表勾选，可多选，立刻生效 |
| **改密码** | 家长菜单 →「修改密码」 | 默认 `123456`，务必换掉 |

各品牌无障碍入口：海信/Vidda 在 `设置 → 系统 → 无障碍`；小米在 `设置 → 更多设置 → 无障碍`；其它大多在 `设置 → 无障碍 → 已下载的服务`。

> **每次装新版 APK，系统会自动关掉无障碍。** 装完回来重开一次，否则开机就停在原桌面。

## 能做什么 / 不能做什么

| ✅ 能 | ❌ 不能 |
|---|---|
| 任何 Android 5.0+ 的电视和盒子 | VIDAA / HomeOS（海信自家 Linux 系统）—— 它跑不了 APK |
| 只锁一个 App，或多选几个 | 挡住会进系统设置改回来的大孩子（Android 限制） |
| 到点自动休息、每日额度 | 让 HOME 键彻底失效（需要 Device Owner，多数国产固件不支持） |
| 两套配色、离线语音播报 | 云端同步、远程看孩子在看什么（刻意不做） |

## 常用命令

| 想要 | 命令 |
|---|---|
| 只开无障碍（开机自启的唯一前提） | `python3 tools/kiosk.py watchdog <电视IP>` |
| 恢复悬浮窗权限（可选） | `python3 tools/kiosk.py overlay <电视IP>` |
| 卸载干净 | `python3 tools/kiosk.py uninstall <电视IP>` |
| 救命：电视没桌面了 | `python3 tools/kiosk.py recover-launcher <电视IP>` |

`<电视IP>` 是占位符，换成真实地址（例如 `192.168.1.50`，不要带尖括号）；设备已连上时可以整段省略。
Windows 下把 `python3` 换成 `python`。`overlay` 也可以直接双击 `tools\fix-overlay.bat`。

## 常见问题

**按 HOME 会闪一下原桌面再被拉回来？**
正常。固件把系统桌面钉死了，壳在 0.3 秒内把你拉回儿童桌面。不影响使用。

**开机不进儿童桌面？**
无障碍被关掉了（装新版 APK 一定会关）。跑一次 `watchdog`，或去电视上重开。

**「临时回原桌面」之后重启，没自动进儿童桌面？**
v1.29 已修。旧版靠开机广播清「放行状态」，部分固件不发这个广播，放行就永久挂着、看门狗以为你在放行。升级即可；旧版临时办法：手动打开一次儿童桌面就恢复了。

**忘了家长密码？**
卸载重装（电视 → 设置 → 应用 → 儿童桌面 → 卸载），配置恢复成 `123456`。

**电视上找不到「网络调试」？**
见 [guide 的「找不到 ADB 开关」](docs/guide.md#找不到-adb-开关海信--vidda)，海信有个 `音量平衡 → 1969` 的工厂菜单。

**能不能连盒子？**
能，而且盒子通常支持 Device Owner，能做到 HOME 键彻底失效，比电视更彻底。

## 文档

| 文件 | 内容 |
|---|---|
| [`SKILL.md`](SKILL.md) | 给 AI 助手的操作手册（推荐先让它读这个） |
| [`docs/guide.md`](docs/guide.md) | 完整手册：各品牌差异、开机自启原理、强锁死路、排障、改皮肤 |
| [`CHANGELOG.md`](CHANGELOG.md) | 每个版本改了什么、踩过哪些坑 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 怎么贡献 |

## 构建

需要 JDK 17 + Android SDK（API 34）。没有 `gradlew`，用本机 Gradle 8.2：

```bash
gradle assembleDebug
```

不想装环境就 fork 仓库，GitHub Actions 会自动编译出 APK（见 `.github/workflows/build.yml`）。

## License

MIT
