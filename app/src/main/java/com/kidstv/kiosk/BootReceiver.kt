package com.kidstv.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 开机自启。
 *
 * 三个坑都在这里处理过：
 * 1. **广播名不统一**：除了 `BOOT_COMPLETED`，部分电视/盒子（以及"快速启动"实现）
 *    发的是 `QUICKBOOT_POWERON`，两个都要听。
 * 2. **stopped 状态**：Android 3.1+，安装后从没被打开过的应用收不到开机广播。
 *    所以至少要在电视上手动打开过一次「儿童桌面」，之后每次开机都收得到。
 * 3. **Android 10+ 禁止后台启动 Activity**：从广播里直接 `startActivity` 会被静默拦截。
 *    豁免条件只有两条：有 SYSTEM_ALERT_WINDOW（海信会周期性回收，靠不住），
 *    或者**拥有被系统绑定的服务**——无障碍服务就是。
 *
 * v1.27 的结论：**开机拉起交给看门狗（KioskWatchService），不在这里硬拉**。
 * 这里只负责拉起计时服务，顺带在"有悬浮窗权限"时抢先拉一次省几秒；
 * 没开无障碍才退回全屏 Intent 通知（系统留给后台应用的后门）。
 *
 * v1.29 起这里**不再负责清「本次放行」**（见 `Config.passOnce`）：实测这个广播在部分
 * 固件上根本不来，"靠广播清状态"会让放行状态永久挂着，开机不自启。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "kiosk_boot"
        private const val NOTIFY_ID = 1002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val boot = Intent.ACTION_BOOT_COMPLETED
        if (action != boot && action != "android.intent.action.QUICKBOOT_POWERON") return

        // 开机一律结束「本次放行」。
        // 注意：这一步只是顺手，**不能指望它**——部分固件不发 BOOT_COMPLETED，
        // 或者广播结束后进程立刻被回收、写盘还没落地。真正的判定在 Config.passOnce
        // 的 getter 里（用 boot_id 判断换了次开机就作废），不依赖任何广播。
        Config.passOnce = false

        if (!Config.locked) return

        GuardService.start(context)

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 主路径：无障碍开着 = 看门狗活着，它开机后 3 分钟内会主动把界面拉起来。
        // 有悬浮窗权限就顺手先拉一次（少等一两秒），没有也不影响。
        if (Kiosk.isWatchServiceEnabled(context)) {
            if (canStartActivityFromBackground(context)) {
                try {
                    context.startActivity(launch)
                } catch (e: Exception) {
                }
            }
            return
        }

        // 没开无障碍：只有悬浮窗权限能直接拉
        if (canStartActivityFromBackground(context)) {
            try {
                context.startActivity(launch)
                return
            } catch (e: Exception) {
                // 落到下面发通知
            }
        }
        postFullScreenLaunch(context, launch)
    }

    /** Android 10 起，只有"能在别的应用上层显示"的应用才允许后台拉起界面 */
    private fun canStartActivityFromBackground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 全屏 Intent 通知：不用打开通知栏，系统直接把界面推上来。
     * 电视上同样有效（来电界面就是这么做的）。
     */
    private fun postFullScreenLaunch(context: Context, launch: Intent) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, 0, launch, flags)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "开机启动",
                // HIGH 才能让全屏 Intent 直接推界面，不然只会在通知栏躺着
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "开机时把儿童桌面拉起来"
                setSound(null, null)
                enableVibration(false)
            }
            try {
                nm.createNotificationChannel(ch)
            } catch (e: Exception) {
            }
        }

        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("儿童桌面")
            .setContentText("正在启动…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()

        try {
            // Android 13+ 要 POST_NOTIFICATIONS 运行时权限；没给的话这条兜底也静默失败，
            // 但那时主路径（悬浮窗豁免）通常也是通的，家长菜单里也能看到缺权限的提示。
            nm.notify(NOTIFY_ID, n)
        } catch (e: Exception) {
        }
    }
}
