package com.kidstv.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 两档锁定都在这里：
 *  - 弱锁：动态启用 HomeAlias，让本应用能被设为默认桌面
 *  - 强锁：被设为 Device Owner 后，用 Lock Task Mode 白名单只放行本应用和目标应用
 */
object Kiosk {

    const val ALIAS = "com.kidstv.kiosk.HomeAlias"

    private fun dpm(ctx: Context): DevicePolicyManager? =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun admin(ctx: Context) = ComponentName(ctx, KioskAdmin::class.java)

    fun isDeviceOwner(ctx: Context): Boolean {
        val manager = dpm(ctx) ?: return false
        return try {
            manager.isDeviceOwnerApp(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun isLockTaskActive(ctx: Context): Boolean {
        val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /** 把白名单和锁屏特性写进系统策略。只有 Device Owner 状态下才有效。 */
    fun applyPolicy(ctx: Context) {
        val manager = dpm(ctx) ?: return
        if (!isDeviceOwner(ctx)) return
        try {
            // 没选目标应用时白名单降级成"只放行自己"。
            // 不能把空串塞进去：部分 OEM 会整批校验，一旦抛异常连自己都进不了白名单，
            // 强锁就静默失效了，而界面上还显示"强锁模式"。
            val allowed = mutableListOf(ctx.packageName)
            allowed.addAll(Config.targets.filter { it.isNotBlank() })
            manager.setLockTaskPackages(admin(ctx), allowed.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.setLockTaskFeatures(admin(ctx), DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }

            // **关键**：把 HOME 强制指到本应用的 HomeAlias。
            // 海信/聚好看这类固件会无视「默认桌面」设置和 HOME 角色，只有 DPM 的
            // 常驻首选活动是它们让不过去的（企业 kiosk 都靠这个）。需要 Device Owner。
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            manager.addPersistentPreferredActivity(
                admin(ctx),
                homeFilter,
                ComponentName(ctx, ALIAS)
            )
            Config.policyOk = true
        } catch (e: Exception) {
            Config.policyOk = false
        }
    }

    /** 彻底锁死：开始 Lock Task，HOME 键直接失效。只有 Config.lockTask 打开时才进 */
    fun enter(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        applyPolicy(activity)
        try {
            activity.startLockTask()
        } catch (e: Exception) {
        }
    }

    fun exitLockTask(activity: Activity) {
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
        }
    }

    /** 撤销 Device Owner。失败时只能恢复出厂或走 ADB remove-active-admin。 */
    fun clearOwner(ctx: Context): Boolean {
        val manager = dpm(ctx) ?: return false
        return try {
            manager.clearDeviceOwnerApp(ctx.packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 启动目标应用。
     *
     * 两个坑：
     * 1. TV 应用多数只声明 LEANBACK，所以两个 category 都要试。
     * 2. **必须带 FLAG_INCLUDE_STOPPED_PACKAGES**：Android 3.1 起，刚安装、还没被打开过的应用
     *    处于 stopped 状态，隐式 Intent 默认**匹配不到**它。少了这个标志，就会出现
     *    「应用明明装在电视上，壳却提示没找到」——实测 Ghosten Player 就是这样翻的车。
     */
    fun launchTarget(ctx: Context, pkg: String = Config.primaryTarget): Boolean {
        val target = pkg.trim()
        if (target.isEmpty()) return false

        // 先问系统要标准启动 Intent，能拿到就用它
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(target)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                return true
            }
        } catch (e: Exception) {
        }

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        val categories = arrayOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            Intent.CATEGORY_LAUNCHER
        )
        for (cat in categories) {
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setPackage(target)
                    .addFlags(flags)
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 试下一个 category
            }
        }
        return false
    }

    /**
     * 无障碍服务有没有被家长打开。
     *
     * 这是**唯一**能同时解决"锁桌面、到点休息、开机自启"的通道：
     * 无障碍服务由系统绑定，属于 Android 10 后台启动限制的豁免情形，
     * 不像悬浮窗权限那样会被海信固件周期性回收。
     */
    fun isWatchServiceEnabled(ctx: Context): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        }.orEmpty()
        val full = "${ctx.packageName}/${KioskWatchService::class.java.name}"
        val short = "${ctx.packageName}/.KioskWatchService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    /** 是不是"真正的桌面"。
     * 不能只看组件是否启用 —— 组件启用只代表"有资格当桌面"，
     * 还得问系统：按 HOME 最后会落到谁身上。
     */
    fun isHomeLocked(ctx: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == ctx.packageName
        } catch (e: Exception) {
            false
        }
    }

    fun setHomeLocked(ctx: Context, enabled: Boolean) {
        try {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, ALIAS),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            return
        }
        if (!enabled) return
        // 优先用 RoleManager 的"设为默认桌面"确认框（一次点击）；
        // 老办法是打开系统的桌面设置页，电视上这个页面经常根本没有入口
        if (requestHomeRole(ctx)) return
        openHomeSettings(ctx)
    }

    /**
     * 系统自带桌面（海信叫聚好看、小米叫小米电视桌面、当贝叫当贝桌面…）的包名。
     *
     * **不写死任何品牌**：换一台电视，这个函数照样给出正确答案，不需要维护品牌表。
     * 探测顺序：
     *   ① 问系统"按 HOME 现在会落到谁" —— 最准，所有品牌通吃
     *   ② 列出所有"有资格当桌面"的应用，挑第一个不是自己的
     *   ③ 还探测不到（系统桌面被停用/藏了）就返回空串，由调用方给通用 ADB 命令
     */
    fun stockLauncherPackage(ctx: Context): String {
        try {
            val resolved = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            val pkg = resolved?.activityInfo?.packageName
            if (!pkg.isNullOrEmpty() && pkg != ctx.packageName) return pkg
        } catch (e: Exception) {
        }
        try {
            val all = ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            val other = all.firstOrNull { it.activityInfo.packageName != ctx.packageName }
            if (other != null) return other.activityInfo.packageName
        } catch (e: Exception) {
        }
        return ""
    }

    /** 系统桌面的显示名（聚好看 / 小米电视桌面 / 当贝桌面…），探测不到时退回「系统桌面」 */
    fun stockLauncherName(ctx: Context): String {
        val pkg = stockLauncherPackage(ctx)
        if (pkg.isEmpty()) return "系统桌面"
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            "系统桌面"
        }
    }

    /** Android 10+ 的「默认桌面」角色申请，系统会弹一个确认框 */
    private fun requestHomeRole(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
            if (!rm.isRoleAvailable(RoleManager.ROLE_HOME)) return false
            if (rm.isRoleHeld(RoleManager.ROLE_HOME)) return false
            val intent = rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openHomeSettings(ctx: Context) {
        val flag = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(flag))
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flag))
            } catch (ignored: Exception) {
            }
        }
    }
}
