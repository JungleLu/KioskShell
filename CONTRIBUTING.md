# 参与贡献（CONTRIBUTING）

欢迎提 Issue 和 Pull Request。这个 App 是给孩子用的电视锁屏桌面，任何改动都请先保证**实机可用**、不会把孩子放出锁定。

## 开发环境

- **JDK 17**
- **Android SDK**：Platform 34、Build-Tools 34.0.0
- **Gradle 8.2**（仓库未附带 `gradlew`；用 Android Studio 打开会自动拉取，或本地装好 Gradle 后执行 `gradle wrapper --gradle-version 8.2` 生成）
- 一台开了 ADB 的安卓电视 / 盒子做真机验证（海信 / 小米 / 索尼 / 当贝等均可）

## 本地构建

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio：`Build → Build Bundle(s)/APK(s) → Build APK(s)`。
也可以把仓库推到 GitHub，由 `.github/workflows/build.yml` 自动出 debug APK（Actions 产物下载）。

## 提交前请注意

- **不要提交** `*.apk`、`local.properties`、任何 `*.keystore` / 签名文件、个人隐私或密钥。`.gitignore` 已默认忽略这些。
- 调试版 APK **不随源码提交**，请从 Releases / Actions 产物获取或自行构建。
- 涉及锁屏 / 看门狗 / 开机自启 / 权限的改动，**务必在真机上走一遍完整流程**（装包 → 授权 → 锁定 → 超时锁屏 → 重启自启），纸面改完很容易漏掉实机才暴露的坑。
- 海信等固件会静默收回 `SYSTEM_ALERT_WINDOW` 等权限，涉及权限的改动要考虑“被收后怎么引导家长重授”。

## 提交规范

- 用中文写 commit message 和 PR 说明都可以，重点说清楚**改了什么、为什么、怎么验证的**。
- 一个大功能拆成几个小提交更好 review。
- 提 PR 前请先 `rebase` / `merge` 最新的 `main`，保证能直接编过。

## 语音文案怎么改（开发用）

两条提示语音由 `tools/voice/make_voice.py` 生成（Qwen3-TTS 克隆 + ffmpeg 后处理），输出写入 `app/src/main/res/raw/`：

- 本机需装好 `qwen_tts`、`torch`、`soundfile` 和 `ffmpeg`。
- 路径全部走环境变量，不用改代码：

  | 变量 | 作用 | 默认值 |
  |---|---|---|
  | `QWEN_TTS_ROOT` | 模型与参考音频的根目录 | 仓库根下的 `voice/` |
  | `QWEN_TTS_MODEL` | 模型目录 | `$QWEN_TTS_ROOT/_models/Qwen3-TTS-12Hz-1.7B-Base` |
  | `VOICE_REF_WAV` | 参考音频（建议 10–15 秒干净人声） | `$QWEN_TTS_ROOT/_ref/ref_female.wav` |
  | `VOICE_OUT` | wav 输出目录 | `voice/out/` |

- 不打算重训音色的话，直接用任意 mp3 覆盖 `res/raw/voice_rest.mp3` / `voice_day_over.mp3` 也行，文件名和时长锚点逻辑不要动。

## 代码约定

- 包名 `com.kidstv.kiosk`，**不写死任何电视品牌 / 系统桌面包名**，探测全部运行时做。
- 主题用 `res/values/attrs.xml` 的 11 个皮肤 attr + `themes.xml` 的两套样式实现，加新皮肤只加样式和同名 drawable，布局不用改（见 README「加一套新皮肤」）。
- 家长验证、时长管控、锁定状态等配置走 `Config.kt` 的 `SharedPreferences`。
