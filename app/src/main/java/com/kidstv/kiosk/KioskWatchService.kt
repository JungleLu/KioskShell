package com.kidstv.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
        /**
         * 日志 tag。
         *
         * **注意级别用 Info 不用 Debug**：海信 VIDAA 把 D 级日志整个关掉了
         * （实测 logcat 5000 行里 0 条 D），写 `Log.d` 等于没写，排查时一条都看不到。
         * Info 只对接了 adb 的人可见，对孩子没有暴露面。
         */
        private const val TAG = "KioskWatch"

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

        /**
         * 窗口事件报上来之后，先等这么久再确认（v1.38）。
         *
         * 固件浮层只闪一下：任何品牌的固件服务都可能在前台应用之上插一条
         * TYPE_WINDOW_STATE_CHANGED（实测案例：海信是 `com.jamdeo.tv.service`，
         * 播放动画片时插进来，紧接着又回到播放器）。立刻按它拉人，就把正在看的孩子弹回桌面了。
         * 等一秒再问系统「现在前台是谁」，这类瞬时窗口早就消失了。
         *
         * **判据是"真实前台是谁"，不是"这条事件的包名在不在黑名单里"**，
         * 所以换任何品牌、任何固件服务都不用改这里。
         */
        private const val CONFIRM_DELAY_MS = 900L

        /**
         * 「前台是谁」这条记录的有效期。
         *
         * 超过这么久没收到窗口切换事件，就不能再拿旧记录当证据去拉人 ——
         * 应用冷启动那 1~3 秒里，旧记录还停在启动前的桌面上，
         * 拿它判断会把人从刚打开的应用里弹回去（v1.31 修的就是这个）。
         */
        private const val EVENT_FRESH_MS = 5000L

        /**
         * 主动探测「现在前台是谁」的间隔（v1.33）。
         *
         * 为什么不能只等事件：海信 VIDAA 按 HOME 切回系统桌面时**不发**
         * `TYPE_WINDOW_STATE_CHANGED`。实测孩子按一下 HOME 就停在聚好看，
         * 而 `lastFg` 还停在儿童桌面自己身上，巡检第一句 `if (lastFg == packageName) return`
         * 直接退出 —— 锁形同虚设。
         *
         * 2 秒一次，只读 `rootInActiveWindow.packageName`，不遍历界面树。
         */
        private const val PROBE_INTERVAL_MS = 2000L

        /** 锁屏页最小重提间隔：孩子按 HOME/返回逃掉时把它再盖回去 */
        private const val LOCK_COOLDOWN_MS = 6000L

        /**
         * 系统界面窗口，不算"跑到别的应用去了"。
         * 状态栏、音量条、电源菜单、授权弹窗都挂在这里，一秒钟能来好几个事件，
         * 不排除的话会把孩子正在看的画面反复打断。
         *
         * **这里只能放 AOSP 固定包名，任何品牌专属包名都不许加进来。**
         * `com.android.systemui` 和 `android` 由 AOSP 定义，所有安卓设备都有；
         * 而各品牌固件服务各不相同（海信 `com.jamdeo.tv.service`、小米和 TCL 又是另一套），
         * 按品牌枚举永远追不完，漏一个就会误拉。
         *
         * v1.38 起品牌浮层由「事件只当线索 + 拉人前读真实前台」吸收，
         * 不需要也不应该在这里维护名单。真遇到某品牌常驻浮层造成误拉，
         * 先看 `adb logcat -s KioskWatch` 里的 `evt=... real=...` 确认真实前台是谁，
         * 再决定怎么处理，不要凭猜加包名。
         */
        private val SYSTEM_UI_PKGS = setOf("com.android.systemui", "android")
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 最后一次见到的前台应用（自己 / systemui / 输入法都不算） */
    private var lastFg = ""

    /** 上面那条记录是什么时候更新的。用来判断它还作不作数 */
    private var lastEventAt = 0L
    private var lastEnforceAt = 0L
    private var lastLockAt = 0L
    private var lastProbeAt = 0L

    /** 「探测读不到包名」这条日志的节流时间：Flutter 应用会一直读不到，不能每 2 秒刷一条 */
    private var lastEmptyLogAt = 0L

    /** 窗口事件报上来的「可疑前台」，等 [CONFIRM_DELAY_MS] 之后再确认 */
    private var pendingPkg: String? = null
    private var pendingAt = 0L
    private var bootTries = 0
    private var connectedAt = 0L

    /** 屏幕亮着吗。待机时不去拉界面，也停掉计时 */
    private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null

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
        lastEventAt = 0L
        bootTries = 0
        connectedAt = SystemClock.elapsedRealtime()
        screenOn = isScreenOn()
        Timer.screenOn = screenOn
        registerScreenReceiver()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        handler.removeCallbacks(ticker)
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        screenReceiver = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(ticker)
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------- 事件：只记「现在前台是谁」 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        if (pkg in SYSTEM_UI_PKGS) return
        if (isCurrentIme(pkg)) return

        /**
         * **宽限期内的窗口事件一律不记**（v1.38）。
         *
         * 通用规则：启动目标应用的那几秒，固件会插一串过渡窗口进前台
         * （实测案例：海信在启动时先把系统桌面 `com.ju.main.launcher` 闪到前台一下）。
         * 旧代码收到就写 [lastFg]，宽限期只是不立刻拉，但**脏值已经落进去了**；
         * 而 Flutter 类应用（`libflutter.so`）既不发 WINDOW_STATE_CHANGED，
         * `rootInActiveWindow` 也读不到包名 —— [probeForeground] 没有任何机会把脏值纠正回来。
         * 于是 6 秒宽限一到，巡检读到 `lastFg = 系统桌面`，当场把孩子弹回桌面。
         * 实测两次都卡在 5.85s / 5.96s，正好等于 [Kiosk.TARGET_GRACE_MS]。
         *
         * 这条规则不看品牌：任何固件在冷启动期都可能甩出过渡窗口，
         * 一律不记即可，不需要知道它是谁。
         *
         * 代价：宽限期内真跑了也不记。6 秒，且下一拍 probe 就能读到（系统桌面不是
         * Flutter，读得到），最多晚几秒才拉回来。
         */
        if (SystemClock.elapsedRealtime() < Kiosk.targetGraceUntil) {
            Log.i(TAG, "evt $pkg (ignored: in grace)")
            return
        }

        Log.i(TAG, "evt $pkg (lastFg was $lastFg)")
        lastFg = pkg
        lastEventAt = SystemClock.elapsedRealtime()
        if (pkg == packageName) bootTries = 0
        // 不在这里直接拉：窗口事件的包名不等于「孩子现在在哪个应用」（理由见 scheduleCheck）
        scheduleCheck(pkg)
    }

    /** 跑到不该去的地方就拉回来 */
    private fun enforce(pkg: String) {
        if (!Config.kioskMode) return
        if (Config.passOnce) return                       // 家长按了「本次放行」
        if (!screenOn) return                             // 待机中拉界面没意义
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        // 刚启动过目标应用：这 6 秒内的事件（广告页、播放器内核、授权弹窗）一律不追究。
        // 少了这句，从桌面点开应用、首帧还没出来就被判成"跑了"，弹回桌面（v1.31）
        if (SystemClock.elapsedRealtime() < Kiosk.targetGraceUntil) return
        if (pkg == packageName) return                    // 自己的桌面/锁屏页
        if (pkg in Config.targets) return                 // 目标应用（可多选），放行

        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforceAt < ENFORCE_COOLDOWN_MS) return
        lastEnforceAt = now
        Log.i(TAG, "PULL src=enforce pkg=$pkg targets=${Config.targets}")
        backToKiosk()
    }

    // ---------------- 窗口事件：先记下，延迟确认 ----------------

    /**
     * 把一条窗口事件记成「待确认」，**不立刻拉人**（v1.38）。
     *
     * 踩过的坑（与品牌无关，任何固件都可能发生）：窗口事件里的包名不等于
     * 「孩子现在在哪个应用」。实测两种常见干扰——
     * 1. 启动应用时固件先把系统桌面闪到前台（海信实测是 `com.ju.main.launcher`）；
     * 2. 播放中固件服务插进来发一条窗口切换（海信实测是 `com.jamdeo.tv.service`）。
     * 信了这两条，孩子看几秒就被弹回桌面（实测两次都卡在 5.85s / 5.96s，
     * 正好等于 [Kiosk.TARGET_GRACE_MS]，也就是宽限一到期就清算）。
     *
     * 而且 Flutter 类应用（`libflutter.so`）既不常发 WINDOW_STATE_CHANGED，
     * `rootInActiveWindow` 也读不到包名 —— 脏记录没有任何机会被自动纠正回来，
     * 所以只能从源头管住：事件只当线索，不当判据。
     *
     * 注意上面两个包名只是"现场记录"，没有参与任何判断 —— 这段逻辑对
     * 海信 / 小米 / TCL / 索尼 / 盒子同样生效，不需要维护品牌表。
     */
    private fun scheduleCheck(pkg: String) {
        if (pkg == packageName) return
        if (pkg in Config.targets) return
        pendingPkg = pkg
        pendingAt = SystemClock.elapsedRealtime()
    }

    /** 待确认的线索过了一秒还没被新事件覆盖，就去问一次真实前台 */
    private fun checkPending() {
        val pkg = pendingPkg ?: return
        if (SystemClock.elapsedRealtime() - pendingAt < CONFIRM_DELAY_MS) return
        pendingPkg = null
        confirmAndEnforce(pkg)
    }

    /**
     * 拉人之前，先问系统「现在前台到底是谁」。
     *
     * - 读到目标应用 / 自己 → 刚才那条是旁路窗口，放过
     * - **读到空** → 前台是不暴露无障碍内容的应用（Flutter、全屏 SurfaceView），
     *   大概率就是正在看的目标应用，放过。真跑到系统桌面时一定读得到，
     *   2 秒内会被 [probeForeground] 抓回来，锁不会漏
     * - 读到别的应用 → 确认跑掉了，拉回
     */
    private fun confirmAndEnforce(evtPkg: String) {
        if (!Config.kioskMode) return
        if (Config.passOnce) return
        if (!screenOn) return
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        if (SystemClock.elapsedRealtime() < Kiosk.targetGraceUntil) return

        val real = readForeground()
        if (real.isEmpty() || real == packageName || real in Config.targets) {
            Log.i(TAG, "skip evt=$evtPkg real=${real.ifEmpty { "(empty)" }}")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforceAt < ENFORCE_COOLDOWN_MS) return
        lastEnforceAt = now
        Log.i(TAG, "PULL src=confirm evt=$evtPkg real=$real targets=${Config.targets}")
        backToKiosk()
    }

    /** 只取包名，不读界面内容和文字 */
    private fun readForeground(): String = try {
        rootInActiveWindow?.packageName?.toString().orEmpty()
    } catch (e: Exception) {
        ""
    }

    // ---------------- 每秒一拍 ----------------

    private fun onBeat() {
        // 心跳：发现中间睡过一大觉就把「本次放行」作废。
        // 这是 ProcId 判据的兜底 —— 万一待机时进程侥幸没被杀，靠 tick 空档也能认出来。
        Config.heartbeat()

        // 主动校正「前台是谁」：有的固件切回系统桌面不发窗口事件，光等事件会一直以为
        // 还在自己桌面上，按 HOME 跑掉也拉不回来（v1.33）
        probeForeground()

        // 窗口事件报上来的「跑到别处了」，等够一秒再确认（v1.38）
        checkPending()

        // GuardService 被系统掐掉时由这里接管计时，否则"看了多久"永远不涨
        val tick = if (GuardService.alive) Timer.TickResult.NONE else Timer.tick()

        if (lockIfTimeUp(tick)) return
        watchBootAndEscape()
    }

    /**
     * 直接问系统「现在前台是哪个应用」，不看事件。
     *
     * 只在 [PROBE_INTERVAL_MS] 跑一次；拿到包名就当一条新鲜事件处理，
     * 这样 [watchBootAndEscape] 手里的 [lastFg] 永远是可信的。
     *
     * 读 `rootInActiveWindow` 需要 `canRetrieveWindowContent="true"`（见 accessibility_config.xml），
     * 这里**只取 packageName**，不碰界面内容和文字。
     */
    private fun probeForeground() {
        if (!Config.kioskMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeAt < PROBE_INTERVAL_MS) return
        lastProbeAt = now
        val pkg = readForeground()
        // 拿不到（视频全屏、SurfaceView、Flutter 的空语义树）就保持原记录，别瞎猜
        if (pkg.isEmpty()) {
            if (now - lastEmptyLogAt > 30_000L) {
                lastEmptyLogAt = now
                Log.i(TAG, "probe empty (keep lastFg=$lastFg)")
            }
            return
        }
        if (pkg in SYSTEM_UI_PKGS) return
        if (isCurrentIme(pkg)) return
        if (pkg == lastFg) return
        // 宽限期内同样不记：启动过渡期的窗口不值得当证据（理由见 onAccessibilityEvent）
        if (now < Kiosk.targetGraceUntil) {
            Log.i(TAG, "probe $pkg (ignored: in grace)")
            return
        }
        Log.i(TAG, "probe $pkg (lastFg was $lastFg)")
        lastFg = pkg
        lastEventAt = now
        enforce(pkg)
    }

    /**
     * 到点盖锁屏页。
     * 走无障碍通道 → 不需要悬浮窗权限，也不会被 Android 10 的后台启动限制拦住。
     */
    private fun lockIfTimeUp(tick: Timer.TickResult): Boolean {
        // 临时放行期间不弹休息页/超额页：家长正在用，计时已由 Timer.shouldCount 停掉，
        // 这里再挡一次，防止放行前遗留的 restUntil / dayBlocked 把页面弹出来。
        if (Config.passOnce) return false
        if (!screenOn) return false                       // 待机时不弹
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
     *
     * **只在"证据新鲜"时动手**（v1.31）：手里的 [lastFg] 来自上一个窗口事件，
     * 应用冷启动那几秒它还是旧值，拿旧值判断就会把人从刚打开的应里弹回来。
     */
    private fun watchBootAndEscape() {
        if (!Config.kioskMode || Config.passOnce) return
        if (!screenOn) return
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        if (SystemClock.elapsedRealtime() < Kiosk.targetGraceUntil) return
        if (lastFg == packageName) return
        if (lastFg in Config.targets) return

        val now = SystemClock.elapsedRealtime()
        // 开机不到 3 分钟（elapsedRealtime 本身就是"开机至今"），或服务刚绑上：
        // 这两种情况都可能一个窗口事件都没收到，事件驱动永远等不到，必须主动拉
        val booting = now < BOOT_WINDOW_MS ||
            (connectedAt > 0 && now - connectedAt < RECONNECT_WINDOW_MS)
        val fresh = lastEventAt > 0 && now - lastEventAt < EVENT_FRESH_MS

        if (lastFg.isEmpty()) {
            // 一次事件都没收到：只在开机/刚重连的窗口里盲拉，其余情况无从判断，别乱拉
            if (!booting) return
            if (bootTries >= BOOT_MAX_TRIES) return
            if (now - lastEnforceAt < BOOT_RETRY_MS) return
            if (!escapeConfirmed()) return
            bootTries++
            lastEnforceAt = now
            Log.i(TAG, "PULL src=boot lastFg=(empty) try=$bootTries")
            backToKiosk()
            return
        }

        // 有记录但过期了：状态很可能已经变了（应用正在启动 / 刚切换），不能据此拉人
        if (!fresh && !booting) return

        if (now - lastEnforceAt < ENFORCE_COOLDOWN_MS) return
        // 最后一道闸：lastFg 来自窗口事件，固件浮层会让它失真（v1.38）
        if (!escapeConfirmed()) return
        lastEnforceAt = now
        Log.i(TAG, "PULL src=watchBoot lastFg=$lastFg fresh=$fresh booting=$booting targets=${Config.targets}")
        backToKiosk()
    }

    /**
     * 兜底巡检动手前的最后一道闸：直接问系统「现在前台是谁」。
     *
     * 返回 true = 确认跑掉了。
     * 返回 false 的三种情况：读到自己 / 读到目标应用 / **什么都读不到**。
     * 最后那种最常见于 Flutter、自绘界面、全屏 SurfaceView 播放器（实测 Ghosten 就是）——
     * 前台就是目标应用，只是它的无障碍树是空的。这与品牌无关，各家播放器都可能读不到。
     * 宁可放过一秒钟，也不能把正在看动画片的孩子弹回桌面。
     *
     * 反过来的代价：某个品牌的系统桌面要是也读不到无障碍树，这一条会变成"漏锁"。
     * 取舍是故意的 —— 误拉（孩子正看着被弹回）比漏锁（孩子多跑出去一会儿）更烦人，
     * 遇到后者先看日志里 `real=(empty)` 出现在哪个包上，再决定怎么补。
     */
    private fun escapeConfirmed(): Boolean {
        val real = readForeground()
        if (real.isEmpty() || real == packageName || real in Config.targets) {
            Log.i(TAG, "confirm skip real=${real.ifEmpty { "(empty)" }} lastFg=$lastFg")
            return false
        }
        return true
    }

    // ---------------- 息屏 / 亮屏 ----------------

    /**
     * 电视的「关机」多数是待机，不走开机广播、`boot_id` 也不变，
     * 所以放行状态必须靠这里收尾，否则第二天开机还是原桌面（v1.31）。
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        screenReceiver = r
        try {
            registerReceiver(r, filter)
        } catch (e: Exception) {
            screenReceiver = null
        }
    }

    private fun onScreenOff() {
        screenOn = false
        // 看门狗在接管计时时要靠这个停表，否则待机时间会被算成观看时间
        Timer.screenOn = false
        Config.noteScreenOff()
    }

    private fun onScreenOn() {
        screenOn = true
        Timer.screenOn = true
        // 睡够 3 分钟：这一轮放行就地作废（家长昨晚用完电视了）
        Config.noteScreenOn()

        if (!Config.kioskMode || Config.passOnce) return
        if (SystemClock.elapsedRealtime() < Config.watchdogPauseUntil) return
        if (SystemClock.elapsedRealtime() < Kiosk.targetGraceUntil) return

        // 唤醒后窗口没变过，不会来新的窗口事件，等事件驱动就永远等不到。
        // 直接判一次：该进桌面就进。
        lastFg = ""
        lastEventAt = 0L
        lastEnforceAt = 0L
        bootTries = 0
        backToKiosk()
    }

    private fun isScreenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            power.isInteractive
        } catch (e: Exception) {
            true
        }
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
