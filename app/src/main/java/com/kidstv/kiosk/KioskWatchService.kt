package com.kidstv.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 看门狗。
 *
 * 为什么不靠"把系统桌面停用"来锁：海信这类固件无视默认桌面设置，停用系统桌面又没法在应用内恢复
 * （普通应用没有启用别的包的权限，电视自己的设置页也没有「启用」按钮），等于把自己锁死。
 *
 * 换个思路：系统桌面留着不动，这里盯着"当前前台是哪个应用"，一旦跑到不是本应用、
 * 也不是目标应用的地方，立刻把儿童桌面拉回来。家长要退出时，在家长菜单里关掉这个模式就行，
 * 电视原封不动。
 *
 * 前提：家长在系统设置里给一次「无障碍」权限。
 */
class KioskWatchService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Config.kioskMode) return
        if (Config.passOnce) return                         // 家长按了「本次放行」
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        if (pkg == packageName) return                      // 自己的桌面/锁屏页
        if (pkg in Config.targets) return                   // 目标应用（可多选），放行
        if (pkg == "com.android.systemui" || pkg == "android") return
        if (isCurrentIme(pkg)) return                       // 输入法弹窗

        val back = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            startActivity(back)
        } catch (e: Exception) {
            // 后台启动被拦时忽略：悬浮窗权限授过之后一般不会再被拦
        }
    }

    override fun onInterrupt() {}

    private fun isCurrentIme(pkg: String): Boolean {
        val ime = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        return ime.startsWith("$pkg/")
    }
}
