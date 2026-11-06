package com.kidstv.kiosk

import android.os.SystemClock

object Config {

    /** 「本次放行」的四个键：值本身 + 写入时刻的 uptime + 当时的 boot_id + 当时的进程代次 */
    private const val KEY_PASS_ONCE = "pass_once"
    private const val KEY_PASS_ONCE_AT = "pass_once_at"
    private const val KEY_PASS_ONCE_BOOT = "pass_once_boot"
    private const val KEY_PASS_ONCE_PROC = "pass_once_proc"

    /** 上次息屏的墙钟时刻。用来判断"这次黑屏睡了多久" */
    private const val KEY_SCREEN_OFF_AT = "screen_off_at"

    /** 看门狗心跳：最后一次"我还活着"的墙钟时刻，用来发现中间有没有睡过一大觉 */
    private const val KEY_HEARTBEAT = "heartbeat_at"

    /**
     * 息屏/待机超过这么久，就认为「这一次放行结束了」。
     *
     * 为什么需要：电视上的「关机」绝大多数是待机（suspend），不是冷启动 ——
     * `boot_id` 不变，于是"换了一次开机才作废"这条判断完全覆盖不到，
     * 放行标记会一直挂着。家长睡前点了「临时回原桌面」，第二天孩子开机，居然还是原桌面。
     *
     * 判据只能用墙钟（`currentTimeMillis()`）：`elapsedRealtime()` 是 `CLOCK_BOOTTIME`，
     * **含**休眠时间，拿它算"睡了多久"永远算不出来。
     *
     * 之所以不直接在息屏广播里记时刻：系统可能先把进程杀掉再发广播（实测过一次），
     * 记录动作没有机会执行。所以这里用"心跳断了多久"来间接判断。
     *
     * 3 分钟是为了放过"换台时闪一下黑屏"这种短息屏。
     */
    private const val PASS_SLEEP_MS = 3 * 60 * 1000L

    /**
     * 长按菜单键多久算"家长要进来"。
     *
     * 放在这里是为了让桌面、锁屏页、按键诊断页用的是同一个数 ——
     * 诊断页要判断"你按住 3 秒，但我们只收到 480ms"，阈值一旦各写各的，那句结论就不可信了。
     */
    const val UNLOCK_LONG_PRESS_MS = 2000L

    /** 「这一轮开机已经自动进过应用」记在哪个 boot_id 上 */
    private const val KEY_AUTO_LAUNCH_BOOT = "auto_launch_boot"

    /**
     * 「开机自动进应用」只在开机后的这段时间内有效。
     *
     * 为什么要有窗口：以前只要桌面回到前台就排队自动跳，孩子按 HOME 想回桌面，
     * 4 秒后又被拽回播放器，等于 HOME 键白按。现在只有开机那一轮会自动进一次。
     * 5 分钟足够覆盖「开机 → 看门狗拉起桌面 → 自动进应用」这条链，
     * 又不会被「午休醒来」这类场景蹭到。
     */
    private const val AUTO_LAUNCH_WINDOW_MS = 5 * 60 * 1000L

    /**
     * 要锁定的目标应用，**可多选**。空集合 = 家长还没选，或者主动选了「不锁定任何应用」。
     * 装哪些 App 由家长在电视上从已安装列表里自己勾，不预设。
     *
     * 旧版本是单选（存在 "target" 键），第一次读的时候自动搬进 "targets"，
     * 升级安装不会丢已选的应用。
     */
    val targets: Set<String>
        get() {
            val sp = App.prefs
            if (!sp.contains("targets")) {
                val legacy = sp.getString("target", "") ?: ""
                val seed = if (legacy.isBlank()) emptySet() else setOf(legacy)
                sp.edit().putStringSet("targets", seed).apply()
            }
            return sp.getStringSet("targets", emptySet()) ?: emptySet()
        }

    fun setTargets(pkgs: Set<String>) {
        App.prefs.edit().putStringSet("targets", pkgs.filter { it.isNotBlank() }.toSet()).apply()
    }

    /** 勾选/取消勾选一个应用 */
    fun toggleTarget(pkg: String) {
        val cur = targets.toMutableSet()
        if (!cur.add(pkg)) cur.remove(pkg)
        setTargets(cur)
    }

    /**
     * 目标应用里的第一个。**只在单选场景下有意义**（自动进入、菜单「打开应用」）。
     * 多选时这个值取决于 HashSet 的迭代顺序，别拿它当"家长选的那个"。
     */
    val primaryTarget: String
        get() = targets.firstOrNull { it.isNotBlank() } ?: ""

    /** 上一次 applyPolicy 是否成功。强锁下用来看"是不是真的锁上了" */
    var policyOk: Boolean
        get() = App.prefs.getBoolean("policy_ok", true)
        set(value) {
            App.prefs.edit().putBoolean("policy_ok", value).apply()
        }

    var pin: String
        get() = App.prefs.getString("pin", "123456") ?: "123456"
        set(value) {
            App.prefs.edit().putString("pin", value).apply()
        }

    /** 家长主动解除锁定后置 false，重启也不会自己锁回去 */
    var locked: Boolean
        get() = App.prefs.getBoolean("locked", true)
        set(value) {
            App.prefs.edit().putBoolean("locked", value).apply()
        }

    var firstRun: Boolean
        get() = App.prefs.getBoolean("first_run", true)
        set(value) {
            App.prefs.edit().putBoolean("first_run", value).apply()
        }

    // ---------------- 时间管理 ----------------

    /** 时长管控总开关。关掉后只锁桌面，不管看了多久 */
    var limitEnabled: Boolean
        get() = App.prefs.getBoolean("limit_on", true)
        set(value) {
            App.prefs.edit().putBoolean("limit_on", value).apply()
        }

    /** 单次连续看多少分钟就强制休息，0 表示不限制 */
    var singleMinutes: Int
        get() = App.prefs.getInt("single_min", 25)
        set(value) {
            App.prefs.edit().putInt("single_min", value).apply()
        }

    /** 每次到点后强制休息多少分钟 */
    var restMinutes: Int
        get() = App.prefs.getInt("rest_min", 5)
        set(value) {
            App.prefs.edit().putInt("rest_min", value).apply()
        }

    /** 每天总共多少分钟，0 表示不限制 */
    var dailyMinutes: Int
        get() = App.prefs.getInt("daily_min", 60)
        set(value) {
            App.prefs.edit().putInt("daily_min", value).apply()
        }

    /** 今天已经看了多少秒，跨天自动归零 */
    var usedSeconds: Int
        get() = if (App.prefs.getString("used_date", "") == Timer.todayKey())
            App.prefs.getInt("used_sec", 0) else 0
        set(value) {
            App.prefs.edit()
                .putString("used_date", Timer.todayKey())
                .putInt("used_sec", value)
                .apply()
        }

    /** 本次休息结束的墙钟时间戳，0 表示不在休息 */
    var restUntil: Long
        get() = App.prefs.getLong("rest_until", 0L)
        set(value) {
            App.prefs.edit().putLong("rest_until", value).apply()
        }

    /** 今天是否已经用完额度，跨天自动清零 */
    var dayBlocked: Boolean
        get() = if (App.prefs.getString("block_date", "") == Timer.todayKey())
            App.prefs.getBoolean("blocked", false) else false
        set(value) {
            App.prefs.edit()
                .putString("block_date", Timer.todayKey())
                .putBoolean("blocked", value)
                .apply()
        }

    // ---------------- 答题解锁 ----------------

    /**
     * 每天最多答对几次可以跳过休息。**0 = 关闭，而且默认就是 0。**
     *
     * 为什么不做单独的开关：0 就是关闭，多一个开关只会让家长问"我该关哪个"。
     * 出厂不开启也很重要 —— 老用户升级后行为必须一点不变，
     * 否则"我装个更新，孩子突然能靠做题躲过休息了"会引起投诉。
     */
    var quizDailyMax: Int
        get() = App.prefs.getInt("quiz_max", 0).coerceIn(0, 5)
        set(value) {
            App.prefs.edit().putInt("quiz_max", value.coerceIn(0, 5)).apply()
        }

    /** 出题年级 1-6，默认三年级（一年级太简单，六年级对小学生偏难，三年级居中） */
    var quizGrade: Int
        get() = App.prefs.getInt("quiz_grade", 3).coerceIn(1, 6)
        set(value) {
            App.prefs.edit().putInt("quiz_grade", value.coerceIn(1, 6)).apply()
        }

    /** 今天已经用掉几次，跨天归零（照 [usedSeconds] 的写法） */
    var quizUsedToday: Int
        get() = if (App.prefs.getString("quiz_date", "") == Timer.todayKey())
            App.prefs.getInt("quiz_used", 0) else 0
        set(value) {
            App.prefs.edit()
                .putString("quiz_date", Timer.todayKey())
                .putInt("quiz_used", value)
                .apply()
        }

    /**
     * 这次休息已经答错几次。
     *
     * 拿 [restUntil] 当"这次休息的身份"：restUntil 一变就是新的一次休息，计数自然归零，
     * 不需要谁记得去重置 —— 少一处"忘记清"的可能。休息页被看门狗反复拉起也不会重置，
     * 否则孩子靠"退出再进"就能刷出无限次机会。
     */
    var quizRestWrong: Int
        get() = if (App.prefs.getLong("quiz_rest_at", -1L) == restUntil)
            App.prefs.getInt("quiz_rest_wrong", 0) else 0
        set(value) {
            App.prefs.edit()
                .putLong("quiz_rest_at", restUntil)
                .putInt("quiz_rest_wrong", value)
                .apply()
        }

    // ---------------- 家长门禁 ----------------

    /** 验证方式：pin / addsub / muldiv，见 Challenge.Mode。默认沿用原来的数字密码，升级后不会被锁在门外 */
    var gateMode: String
        get() = App.prefs.getString("gate_mode", "pin") ?: "pin"
        set(value) {
            App.prefs.edit().putString("gate_mode", value).apply()
            clearChallenge()
        }

    /** 当前这道题（落盘：进程被杀重建后不能换题，否则家长正在算的题会突然变） */
    var chPrompt: String
        get() = App.prefs.getString("ch_prompt", "") ?: ""
        set(value) {
            App.prefs.edit().putString("ch_prompt", value).apply()
        }

    var chAnswer: String
        get() = App.prefs.getString("ch_answer", "") ?: ""
        set(value) {
            App.prefs.edit().putString("ch_answer", value).apply()
        }

    var chBornAt: Long
        get() = App.prefs.getLong("ch_born", 0L)
        set(value) {
            App.prefs.edit().putLong("ch_born", value).apply()
        }

    /** 连续答错次数，答对清零 */
    var gateFails: Int
        get() = App.prefs.getInt("gate_fails", 0)
        set(value) {
            App.prefs.edit().putInt("gate_fails", value).apply()
        }

    /**
     * 冷却结束时刻，用 elapsedRealtime 记（改系统时间绕不过去）。
     *
     * 但 elapsedRealtime 每次开机归零，存下来的绝对值重启后会变成"未来的某个时刻"
     * （可能是几天后），家长重启一次就再也输不进密码。所以配 [readDeadline] 用。
     */
    var gateCoolUntil: Long
        get() = readDeadline("gate_cool")
        set(value) = writeDeadline("gate_cool", value)

    fun clearChallenge() {
        App.prefs.edit()
            .remove("ch_prompt")
            .remove("ch_answer")
            .remove("ch_born")
            .apply()
    }

    // ---------------- 语音 ----------------

    /** 到点是否播语音提示 */
    var voiceEnabled: Boolean
        get() = App.prefs.getBoolean("voice_on", true)
        set(value) {
            App.prefs.edit().putBoolean("voice_on", value).apply()
        }

    /**
     * 开机后自动进入目标应用（省得孩子每次再点一下）。
     * **默认关** —— 桌面停住不动、等孩子自己点，比"到点就跳"更好预期。
     * 想要自动进入的，在 观看时长设置 里打开。
     *
     * 注意：只在**开机窗口内**生效一次，见 [autoLaunchDue]。见下一条说明。
     */
    var autoLaunch: Boolean
        get() = App.prefs.getBoolean("auto_launch", false)
        set(value) {
            App.prefs.edit().putBoolean("auto_launch", value).apply()
        }

    /**
     * 现在该不该自动进入应用。
     *
     * 三个条件同时成立才算：
     * ① 家长打开了「开机自动进应用」；
     * ② **只选了一个应用**（见下）；
     * ③ 开机还没超过 [AUTO_LAUNCH_WINDOW_MS]，且这一轮开机**还没自动进过**。
     *
     * 为什么"只选一个"才算：多选时根本没有"该进哪个"的答案。
     * `targets` 是 SharedPreferences 的 StringSet，底层是 HashSet，
     * 迭代顺序跟家长勾选的先后无关 —— 以前多选就是"开机进哪个看运气"。
     * 与其猜，不如停在桌面让孩子自己点卡片（卡片本来就支持多选）。
     *
     * 为什么不"只要回到桌面就跳"：孩子按 HOME 就是想回桌面，
     * 以前被看门狗拉回来之后 4 秒又被送进播放器，等于 HOME 键白按。
     * 现在开机那一轮照旧自动进应用，之后回桌面就老实停在桌面。
     */
    fun autoLaunchDue(): Boolean {
        if (!autoLaunch) return false
        if (targets.size != 1) return false
        // elapsedRealtime 含休眠时间，睡过一夜就自然超出窗口，不会第二天唤醒又跳
        if (SystemClock.elapsedRealtime() > AUTO_LAUNCH_WINDOW_MS) return false
        return App.prefs.getString(KEY_AUTO_LAUNCH_BOOT, "").orEmpty() != BootId.current
    }

    /** 记下"这一轮开机已经自动进过了"，之后回桌面不再跳 */
    fun markAutoLaunched() {
        // commit：这个标记丢了就意味着"按 HOME 还会被弹进应用"，宁可贵一点也要立刻落盘
        App.prefs.edit().putString(KEY_AUTO_LAUNCH_BOOT, BootId.current).commit()
    }

    /**
     * 看门狗模式：无障碍服务盯着前台，孩子跑到别的应用就立刻拉回儿童桌面。
     * 这是这台海信电视唯一能"锁得住又能自己退出"的做法。
     */
    var kioskMode: Boolean
        get() = App.prefs.getBoolean("kiosk_mode", false)
        set(value) {
            App.prefs.edit().putBoolean("kiosk_mode", value).apply()
        }

    /**
     * 看门狗暂停到这个时刻（elapsedRealtime 毫秒）。家长去系统设置时别被弹回来。
     *
     * 这个值如果跨了重启没作废，看门狗会一直以为"家长还在设置里"，
     * 开机自启和 HOME 拉回**一起失效**，而且毫无提示。所以必须按开机次作废。
     */
    var watchdogPauseUntil: Long
        get() = readDeadline("watch_pause")
        set(value) = writeDeadline("watch_pause", value)

    /**
     * 本次放行：这几天想自己看会儿电视，先回聚好看，但**不清除锁定**。
     * 重启电视之后照旧锁回儿童桌面。
     *
     * **重启判定不靠开机广播**（v1.29）。以前的写法是"放行时置 true，`BootReceiver`
     * 收到开机广播再清掉"，实测会漏：部分固件不发 `BOOT_COMPLETED`，或者发了但广播
     * 结束后进程立刻被回收、`apply()` 的异步写盘还没落地。结果是 `pass_once` 一直挂着
     * true，看门狗以为家长还在放行，开机不拉界面；等家长手动打开儿童桌面，
     * `onResume` 才发现并清掉 —— 表现就是"放行后重启不自启，重开一次才恢复"。
     *
     * 现在的做法：放行时顺手记下「boot_id + 当时的 uptime」，读的时候发现换了次开机
     * 就自动作废，不依赖任何广播，也不需要谁来清。
     */
    var passOnce: Boolean
        get() {
            val sp = App.prefs
            if (!sp.getBoolean(KEY_PASS_ONCE, false)) return false

            // 三条判据彼此独立，任一命中就作废。注意**不能像旧版那样"没重启就直接 return true"**，
            // 否则走不到进程代次那条 —— 而待机场景下 boot_id 恰恰不变（v1.32 的坑）。

            // ① 换过一次开机
            // at == 0 说明是旧版本写下的（那时还没有这两个键），无从判断，直接作废
            val rebooted = BootId.sinceReboot(
                sp.getLong(KEY_PASS_ONCE_AT, 0L),
                sp.getString(KEY_PASS_ONCE_BOOT, "").orEmpty()
            )

            // ② 换过一代进程（v1.32）
            // 待机时系统会在息屏广播之前就把进程杀掉，"记下息屏时刻"那套代码根本没机会执行；
            // 进程能换一代，说明中间一定经历过待机或重启 —— 这是最可靠的判据。
            // 家长只是换台闪黑时进程不会死，所以不会误清。
            // 空值 = 旧版本写的，同样按换过处理。
            val recordedProc = sp.getString(KEY_PASS_ONCE_PROC, "").orEmpty()
            val procChanged = recordedProc.isEmpty() || recordedProc != ProcId.current

            // ③ 进程侥幸没死，但黑屏睡过头
            val slept = sp.getLong(KEY_SCREEN_OFF_AT, 0L)
                .let { it > 0L && System.currentTimeMillis() - it >= PASS_SLEEP_MS }

            if (rebooted || procChanged || slept) {
                sp.edit()
                    .putBoolean(KEY_PASS_ONCE, false)
                    .remove(KEY_SCREEN_OFF_AT)
                    .commit()
                return false
            }
            return true
        }
        set(value) {
            // 用 commit 而不是 apply：这个值决定"重启后锁不锁"，丢了就要等家长手动打开才恢复
            App.prefs.edit()
                .putBoolean(KEY_PASS_ONCE, value)
                .putLong(KEY_PASS_ONCE_AT, SystemClock.elapsedRealtime())
                .putString(KEY_PASS_ONCE_BOOT, BootId.current)
                .putString(KEY_PASS_ONCE_PROC, ProcId.current)
                // 清掉上一轮的息屏时刻：否则"上次睡了 10 小时"的记录会立刻把刚开的放行作废
                .remove(KEY_SCREEN_OFF_AT)
                .commit()
        }

    /**
     * 看门狗心跳。每个 tick 调一次，返回 true 表示「中间睡过一大觉，放行已作废」。
     *
     * 它补的是第 ② 条判据的漏洞：万一待机时进程侥幸没被杀，`ProcId` 判不出来，
     * 但 handler 停下来这么久本身就是证据 —— 正常运行时每 1000ms 必有一次 tick。
     *
     * 基线每 60 秒才刷新一次盘（够用且省 flash）：睡 3 分钟以上，醒来第一次 tick
     * 算出的空档必然超过 [PASS_SLEEP_MS]。
     */
    fun heartbeat(): Boolean {
        val sp = App.prefs
        val now = System.currentTimeMillis()
        val last = sp.getLong(KEY_HEARTBEAT, 0L)
        if (last > 0L && now - last >= PASS_SLEEP_MS) {
            sp.edit().remove(KEY_HEARTBEAT).commit()
            if (!sp.getBoolean(KEY_PASS_ONCE, false)) return false
            passOnce = false
            return true
        }
        if (last <= 0L || now - last >= 60_000L) {
            sp.edit().putLong(KEY_HEARTBEAT, now).apply()
        }
        return false
    }

    /** 记下息屏时刻。广播里调用，必须同步落盘（进程可能在休眠中被回收） */
    fun noteScreenOff() {
        App.prefs.edit().putLong(KEY_SCREEN_OFF_AT, System.currentTimeMillis()).commit()
    }

    /**
     * 亮屏调用。返回 true 表示这次放行被"睡过头"作废了。
     *
     * 幂等：先算先清，第二个调用方读到的是已清空，不会重复处理。
     */
    fun noteScreenOn(): Boolean {
        val sp = App.prefs
        val at = sp.getLong(KEY_SCREEN_OFF_AT, 0L)
        if (at <= 0L) return false
        sp.edit().remove(KEY_SCREEN_OFF_AT).commit()
        // 墙钟差；改系统时间会让判断失准，但那是家长自己作弊，不防
        if (System.currentTimeMillis() - at < PASS_SLEEP_MS) return false
        if (!sp.getBoolean(KEY_PASS_ONCE, false)) return false
        passOnce = false
        return true
    }

    /**
     * 彻底锁死：Device Owner 下进入 Lock Task，HOME 键和最近任务直接失效。
     * 默认关 —— 开着的话在目标应用里按 HOME 也回不来，家长只能一路按返回。
     */
    var lockTask: Boolean
        get() = App.prefs.getBoolean("lock_task", false)
        set(value) {
            App.prefs.edit().putBoolean("lock_task", value).apply()
        }

    /**
     * 悬浮窗权限被系统收回的标记。GuardService 每秒巡检时发现
     * `canDrawOverlays` 变 false 就置位，家长授予后回桌面自动清除。
     *
     * 为什么要这个：海信固件实测会**无声撤销** SYSTEM_ALERT_WINDOW
     * （2026-10-23 抓到一次：装完 18 分钟后 allow 变 ignore，期间无安装无重启）。
     * 丢了这个权限，到点盖不出休息页、开机也可能拉不起界面，而且界面上毫无提示。
     */
    var overlayLost: Boolean
        get() = App.prefs.getBoolean("overlay_lost", false)
        set(value) {
            App.prefs.edit().putBoolean("overlay_lost", value).apply()
        }

    // ---------------- 皮肤 ----------------

    /**
     * 主题：cream 奶油儿童（默认）/ space 深空影院。
     * 家长菜单里「切换主题」改，改完全局立即生效（活动 recreate）。
     */
    var theme: String
        get() = App.prefs.getString("theme", KioskTheme.CREAM) ?: KioskTheme.CREAM
        set(value) {
            App.prefs.edit().putString("theme", value).apply()
        }

    // ---------------- 跨重启的状态 ----------------

    /**
     * 读一个「uptime 基准的截止时刻」。
     *
     * `elapsedRealtime()` 每次开机从 0 开始，所以把它存进 prefs 再加个偏移量，
     * 一旦跨了重启，这个绝对值就会指向"重启后还要过很久"的将来 ——
     * 表现是看门狗被永久暂停（不自启、不拉回）、或者家长被永久锁在密码冷却里。
     *
     * 所以写入时一并记下 boot_id，读的时候发现换了次开机就直接作废。
     */
    private fun readDeadline(key: String): Long {
        val sp = App.prefs
        val v = sp.getLong(key, 0L)
        if (v <= 0L) return 0L
        val at = sp.getLong("${key}_at", 0L)
        val boot = sp.getString("${key}_boot", "").orEmpty()
        if (!BootId.sinceReboot(at, boot)) return v
        sp.edit().putLong(key, 0L).commit()
        return 0L
    }

    private fun writeDeadline(key: String, value: Long) {
        App.prefs.edit()
            .putLong(key, value)
            .putLong("${key}_at", SystemClock.elapsedRealtime())
            .putString("${key}_boot", BootId.current)
            .commit()
    }
}
