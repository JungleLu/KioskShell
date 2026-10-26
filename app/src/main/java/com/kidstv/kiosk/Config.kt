package com.kidstv.kiosk

import android.os.SystemClock

object Config {

    /** 「本次放行」的三个键：值本身 + 写入时刻的 uptime + 当时的 boot_id，后两个用来判断重启 */
    private const val KEY_PASS_ONCE = "pass_once"
    private const val KEY_PASS_ONCE_AT = "pass_once_at"
    private const val KEY_PASS_ONCE_BOOT = "pass_once_boot"

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

    /** 第一个目标应用：自动进入、菜单「打开应用」用它 */
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
     * 桌面只做中转：选好应用后停留几秒自动进去。
     * **默认关** —— 桌面停住不动、等孩子自己点，比"到点就跳"更好预期。
     * 想要自动进入的，在 观看时长设置 里打开。
     */
    var autoLaunch: Boolean
        get() = App.prefs.getBoolean("auto_launch", false)
        set(value) {
            App.prefs.edit().putBoolean("auto_launch", value).apply()
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
            // at == 0 说明是旧版本写下的（那时还没有这两个键），无从判断，直接作废
            if (!BootId.sinceReboot(sp.getLong(KEY_PASS_ONCE_AT, 0L), sp.getString(KEY_PASS_ONCE_BOOT, "").orEmpty())) {
                return true
            }
            sp.edit().putBoolean(KEY_PASS_ONCE, false).commit()
            return false
        }
        set(value) {
            // 用 commit 而不是 apply：这个值决定"重启后锁不锁"，丢了就要等家长手动打开才恢复
            App.prefs.edit()
                .putBoolean(KEY_PASS_ONCE, value)
                .putLong(KEY_PASS_ONCE_AT, SystemClock.elapsedRealtime())
                .putString(KEY_PASS_ONCE_BOOT, BootId.current)
                .commit()
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
