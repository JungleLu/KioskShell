package com.kidstv.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 打开系统设置页的通用实现（v1.40）。
 *
 * **为什么不能只发一个 action 就完事**：`android.settings.SETTINGS` 和
 * `android.settings.ACCESSIBILITY_SETTINGS` 只是 AOSP 的"约定"，固件可以完全不声明。
 * 实测海信有，小米电视（设置是自家应用）两个都没人接 —— `startActivity` 抛
 * ActivityNotFoundException，家长看到的就是"打不开系统设置"，然后只能干瞪眼。
 *
 * 所以这里按"能不能真打开"逐级降级，**全程不认品牌，只认能力**：
 *   ① AOSP 标准页（能落在要开的那一屏最好）
 *   ② 在「设置类应用」里按类名找对应的页（包名不写死，谁的固件都这么筛）
 *   ③ 退回设置首页，让家长自己翻一层
 *   ④ 全都不行 → 返回 [Landing.NOTHING]，由调用方给"手把手路径"，别只弹个 Toast
 */
object SettingsLauncher {

    private const val TAG = "KioskSettings"

    /** 落到哪一层。调用方据此决定要不要提醒"你自己翻一层" */
    enum class Landing {
        /** 直接落在无障碍页 —— 家长只需拨一下开关 */
        ACCESSIBILITY,

        /** 落在设置首页 —— 家长自己翻一层 */
        SETTINGS_ROOT,

        /** 落在设置类应用里的某个页（固件自己的入口，返回键能回首页） */
        OTHER_PAGE,

        /** 一个都打不开 */
        NOTHING
    }

    /** 无障碍页的类名关键词 */
    private val ACCESSIBILITY_WORDS = listOf("accessibility")

    /**
     * 设置首页的类名关键词，命中越靠前越优先。
     *
     * 别用光秃秃的 `settings` —— 实测海信上它匹配到 246 个页面（`com.android.settings`
     * 里每一个 `Settings$XxxActivity` 都算），挑出来的是哪个看运气。只认"入口感"强的词。
     */
    private val ROOT_WORDS = listOf(
        "mainsettings", "mainactivity", "settingshome", "settingsmain", "homeactivity", "dashboard"
    )

    /**
     * 包名里带这些词，才当成"设置类应用"。
     * 不写死任何厂商包名：小米叫 com.xiaomi.mitv.settings，海信是 com.android.tv.settings，
     * 别家还有 com.tcl.settings / com.sony.settings …… 追名单永远追不完，只能按特征筛。
     */
    private val SETTINGS_PKG_WORDS = listOf("setting")

    /** 名字像设置、其实没有界面的包，排除掉（数据库 provider 之类） */
    private val SKIP_PKG_WORDS = listOf("provider", "overlay", "shared", "backup")

    // ---------------- 对外 ----------------

    /** 打开无障碍设置。这是"权限掉了之后自救"唯一的路 */
    fun openAccessibility(context: Context): Landing {
        // ① 标准页
        if (launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            return Landing.ACCESSIBILITY
        }
        Log.i(TAG, "a11y: 固件不认 ACTION_ACCESSIBILITY_SETTINGS，开始找备用入口")

        // ② 固件自己的无障碍页：只在设置类应用里找，按类名匹配
        for (cn in discovered(context, ACCESSIBILITY_WORDS)) {
            if (launchComponent(context, cn)) return Landing.ACCESSIBILITY
        }

        // ③ Android 13+ 的"单个无障碍服务详情页"，用字符串常量，老 SDK 也能编译
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(
                "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                ComponentName(context, KioskWatchService::class.java).flattenToString()
            )
        if (launch(context, details)) return Landing.ACCESSIBILITY

        // ④ 退到设置首页，让家长自己翻
        val root = openSettings(context)
        return if (root == Landing.NOTHING) Landing.NOTHING else Landing.SETTINGS_ROOT
    }

    /** 打开系统设置首页 */
    fun openSettings(context: Context): Landing {
        if (launch(context, Intent(Settings.ACTION_SETTINGS))) return Landing.SETTINGS_ROOT

        // 备用一：设置类应用的启动入口。只要固件给它留了图标，这条就一定能进去
        for (pkg in settingsPackages(context)) {
            if (launchPackageEntry(context, pkg)) return Landing.OTHER_PAGE
        }

        // 备用二：类名像"首页"的页面
        for (cn in discovered(context, ROOT_WORDS)) {
            if (launchComponent(context, cn)) return Landing.OTHER_PAGE
        }

        // 备用三："关于本机"这类子页。进得去总比进不去强，返回键一般能到首页
        if (launch(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) return Landing.OTHER_PAGE

        Log.i(TAG, "settings: 所有入口都打不开，pkgs=${settingsPackages(context)}")
        return Landing.NOTHING
    }

    /** 打开"悬浮窗权限"页（可选权限，进不去也不影响锁定） */
    fun openOverlay(context: Context, pkg: String): Landing {
        if (launch(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))) {
            return Landing.OTHER_PAGE
        }
        if (launch(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) return Landing.OTHER_PAGE
        return openSettings(context)
    }

    /**
     * 给「按键诊断」页用的一行摘要：三个入口各自能不能打开。
     * 远程用户说不清"哪个点不动"时，让他拍这一页就够了。
     */
    fun probeSummary(context: Context): String {
        val a11y = canResolve(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        val root = canResolve(context, Intent(Settings.ACTION_SETTINGS))
        val pkgs = settingsPackages(context).size
        val backup = discovered(context, ACCESSIBILITY_WORDS).size
        return "无障碍${check(a11y)} 首页${check(root)} 包${pkgs}个 备选${backup}项"
    }

    // ---------------- 内部 ----------------

    private fun check(ok: Boolean) = if (ok) "✓" else "✗"

    private fun canResolve(context: Context, intent: Intent): Boolean =
        startable(context, intent).isNotEmpty()

    /** 系统能启动的候选（已过滤掉停用、没导出的） */
    private fun startable(context: Context, intent: Intent): List<android.content.pm.ActivityInfo> {
        val list = try {
            context.packageManager.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        return list.mapNotNull { it.activityInfo }.filter { it.enabled && it.exported }
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        for (ai in startable(context, intent)) {
            val explicit = Intent(intent)
                .setComponent(ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startQuietly(context, explicit, "${ai.packageName}/${ai.name}")) return true
        }
        return false
    }

    private fun launchComponent(context: Context, cn: ComponentName): Boolean =
        startQuietly(
            context,
            Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            cn.flattenToShortString()
        )

    private fun launchPackageEntry(context: Context, pkg: String): Boolean {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            null
        } ?: return false
        return startQuietly(
            context,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "$pkg（启动入口）"
        )
    }

    /** 真正的 start，异常一律吞掉并记日志 —— 这一层只回答"开没开"，不负责提示 */
    private fun startQuietly(context: Context, intent: Intent, what: String): Boolean = try {
        context.startActivity(intent)
        Log.i(TAG, "ok $what")
        true
    } catch (e: Exception) {
        Log.i(TAG, "fail $what: ${e.javaClass.simpleName}")
        false
    }

    /** 装了的「设置类应用」包名 */
    private fun settingsPackages(context: Context): List<String> {
        val all = try {
            context.packageManager.getInstalledPackages(0).map { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        return all.filter { p ->
            val n = p.lowercase()
            SETTINGS_PKG_WORDS.any { n.contains(it) } && SKIP_PKG_WORDS.none { n.contains(it) }
        }
    }

    /**
     * 在设置类应用里，按类名关键词找可以启动的 Activity（越小越优先）。
     * 关键词全部是英文类名片段，跟品牌无关。
     */
    private fun discovered(context: Context, words: List<String>): List<ComponentName> {
        val pm = context.packageManager
        val ranked = mutableListOf<Pair<Int, ComponentName>>()
        for (pkg in settingsPackages(context)) {
            @Suppress("DEPRECATION")
            val info = try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            } catch (e: Exception) {
                continue
            }
            for (a in info.activities.orEmpty()) {
                if (!a.enabled || !a.exported) continue
                val cls = a.name.lowercase()
                val rank = words.indexOfFirst { cls.contains(it) }
                if (rank >= 0) ranked.add(rank to ComponentName(pkg, a.name))
            }
        }
        return ranked.sortedBy { it.first }.map { it.second }.distinct().take(6)
    }
}
