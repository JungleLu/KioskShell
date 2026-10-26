package com.kidstv.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 看门狗 + 到点锁屏 + 开机拉起，三件事都在这一个服务里。
 *
 * 为什么不靠"把系统桌面停用"来锁：海信这类固件无视默认桌面设置，停用系统桌面又没法在应用内恢复
 * （普通应用没有启用别的包的权限，电视自己的设置页也没有「启用」按钮），等于把自己锁死。
 *
 * 换个思路：系统桌面留着不动，这里盯着"当前前台是哪个应用"，一旦跑到不是本应用、
 * 也不是目标应用的地方，立刻把儿童桌面拉回来。家长要退出时，在家长菜单里关掉这个模式就行，
 * 电视原封不动。
 *
 * **为什么到点锁屏和开机自启也放进这里**（v1.27）：
 * Android 10 起禁止后台启动界面，豁免名单里有一条是「服务被系统绑定」——无障碍服务正是被
 * AccessibilityManagerService 绑定的。所以从这个服务里 startActivity 不受限制，
 * 而 GuardService（普通前台服务）和 BootReceiver（广播接收器）都会被静默拦掉。
 * 实测：海信 VIDAA 会周期性回收 SYSTEM_ALERT_WINDOW（4~6 分钟一次，无日志），
 * 以前"到点弹休息页""开机拉起界面"全靠它，权限一丢就哑了；改走这条通道后与它无关。
 *
 * 前提：家长在系统设置里给一次「无障碍」权限。
 */
class KioskWatchService : AccessibilityService() {

    companion object {
        /** 服务是否连着。家长菜单用它区分"已锁定"和"缺无障碍权限" */
        @Volatile
        var connected = false
            private set

        /** 开机后这段时间内主动重试拉起（此时可能一个窗口事件都还没收到） */
        private const val BOOT_WINDOW_MS = 180_000L

        /** 服务刚绑定的这段时间内也按"还没收到事件"处理（系统升级/重启服务后常见） */
        private const val RECONNECT_WINDOW_MS = 30_000L

        /** 开机窗口内的重试间隔，以及最多重试次数（防止无意义地刷） */
        private const val BOOT_RETRY_MS = 3000L
        private const val BOOT_MAX_TRIES = 25

        /** 常态下"被拉到别处"后的最小重拉间隔 */
        private const val ENFORCE_COOLDOWN_MS = 1500L

        /** 锁屏页最小重提间隔：孩子按 HOME/返回逃掉时把它再盖回去 */
        private const val LOCK_COOLDOWN_MS = 6000L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 最后一次见到的前台应用（自己 / systemui / 输入法都不算） */
    private var lastFg = ""
    private var lastEnforceAt = 0L
    private var lastLockAt = 0L
    private var bootTries = 0
    private var connectedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try {
                onBeat()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        lastFg = ""
        bootTries = 0
        connectedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        handler.removeCallbacks(ticker)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------- 事件：只记「现在前台是谁」 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        if (pkg == "com.android.systemui" || pkg == "android") return
        if (isCurrentIme(pkg)) return

        lastFg = pkg
        if (pkg == packageName) bootTries = 0
        enforce(pkg)
    }

    /** 跑到不该去的地方就拉回来 */
    private fun enforce(pkg: String) {
        if (!Config.kioskMode) return
        if (Config.passOnce) return                       // 家长按了「本次放行」
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        if (pkg == packageName) return                    // 自己的桌面/锁屏页
        if (pkg in Config.targets) return                 // 目标应用（可多选），放行

        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforceAt < ENFORCE_COOLDOWN_MS) return
        lastEnforceAt = now
        backToKiosk()
    }

    // ---------------- 每秒一拍 ----------------

    private fun onBeat() {
        // GuardService 被系统掐掉时由这里接管计时，否则"看了多久"永远不涨
        val tick = if (GuardService.alive) Timer.TickResult.NONE else Timer.tick()

        if (lockIfTimeUp(tick)) return
        watchBootAndEscape()
    }

    /**
     * 到点盖锁屏页。
     * 走无障碍通道 → 不需要悬浮窗权限，也不会被 Android 10 的后台启动限制拦住。
     */
    private fun lockIfTimeUp(tick: Timer.TickResult): Boolean {
        if (!Config.locked || !Config.limitEnabled) return false
        val resting = tick == Timer.TickResult.REST || Timer.restRemainingSeconds() > 0
        val dayOver = tick == Timer.TickResult.DAY_OVER || Config.dayBlocked
        if (!resting && !dayOver) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastLockAt < LOCK_COOLDOWN_MS) return true   // 已经盖着了，别再刷
        lastLockAt = now
        openLock(resting)
        return true
    }

    /**
     * 兜底巡检：
     * 1) 开机后 3 分钟内，可能一个窗口事件都没收到，这里主动拉；
     * 2) 常态下如果停在别处（事件丢了 / 被别的应用盖住），也拉回来。
     */
    private fun watchBootAndEscape() {
        if (!Config.kioskMode || Config.passOnce) return
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        if (lastFg == packageName) return
        if (lastFg in Config.targets) return

        val now = SystemClock.elapsedRealtime()
        // 开机不到 3 分钟（elapsedRealtime 本身就是"开机至今"），或服务刚绑上：
        // 这两种情况都可能一个窗口事件都没收到，事件驱动永远等不到，必须主动拉
        val booting = now < BOOT_WINDOW_MS ||
            (connectedAt > 0 && now - connectedAt < RECONNECT_WINDOW_MS)

        val cooldown = if (lastFg.isEmpty() && booting) BOOT_RETRY_MS else ENFORCE_COOLDOWN_MS
        if (now - lastEnforceAt < cooldown) return

        if (lastFg.isEmpty()) {
            if (!booting) return                  // 无从判断，别乱拉
            if (bootTries >= BOOT_MAX_TRIES) return
            bootTries++
        }
        lastEnforceAt = now
        backToKiosk()
    }

    // ---------------- 启动界面 ----------------

    private fun backToKiosk() {
        val back = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            startActivity(back)
        } catch (e: Exception) {
        }
    }

    private fun openLock(rest: Boolean) {
        val intent = Intent(this, LockActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(
                LockActivity.EXTRA_MODE,
                if (rest) LockActivity.MODE_REST else LockActivity.MODE_DAY
            )
        try {
            startActivity(intent)
        } catch (e: Exception) {
        }
    }

    private fun isCurrentIme(pkg: String): Boolean {
        val ime = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        return ime.startsWith("$pkg/")
    }
}
