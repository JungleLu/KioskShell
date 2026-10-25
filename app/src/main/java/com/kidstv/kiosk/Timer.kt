package com.kidstv.kiosk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计时核心。
 *
 * 只统计「屏幕亮着 + 桌面不在前台 + 不在休息 + 今天还没超额」的时间。
 * 桌面在本应用里，所以 launcher 一回到前台就停止计时，不依赖任何特殊权限
 * （不需要 UsageStats，也不用读别的应用在不在前台）。
 */
object Timer {

    /** 离开超过这么久再回来，算新的一次观看，单次时长重新计 */
    private const val AWAY_RESET_MS = 120_000L

    /** 每 5 秒落一次盘，别把电视的闪存写成消耗品 */
    private const val FLUSH_TICKS = 5

    @Volatile
    var watching = false
        private set

    @Volatile
    var screenOn = true

    /** 这一次已经连续看了多少秒 */
    var sessionSeconds = 0
        private set

    private var usedCache = -1
    private var usedDate = ""
    private var awaySince = 0L
    private var pending = 0

    enum class TickResult { NONE, REST, DAY_OVER }

    fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    // ---------------- 状态切换 ----------------

    fun setWatching(value: Boolean) {
        if (value == watching) return
        watching = value
        if (value) {
            if (awaySince > 0 && System.currentTimeMillis() - awaySince > AWAY_RESET_MS) {
                sessionSeconds = 0
            }
            awaySince = 0
        } else {
            awaySince = System.currentTimeMillis()
            flush()
        }
    }

    fun onRestDone() {
        sessionSeconds = 0
    }

    // ---------------- 读数 ----------------

    fun restRemainingSeconds(): Int {
        val until = Config.restUntil
        if (until <= 0) return 0
        val left = ((until - System.currentTimeMillis()) / 1000L).toInt()
        return if (left > 0) left else 0
    }

    fun startRest(): Boolean {
        sessionSeconds = 0
        if (Config.restMinutes <= 0) {
            flush()
            return false
        }
        Config.restUntil = System.currentTimeMillis() + Config.restMinutes * 60_000L
        flush()
        return true
    }

    fun endRestEarly() {
        Config.restUntil = 0
        sessionSeconds = 0
    }

    fun dayUsedSeconds(): Int {
        loadUsed()
        return usedCache
    }

    /** -1 表示不限 */
    fun dayRemainingSeconds(): Int {
        val limit = Config.dailyMinutes
        if (limit <= 0) return -1
        val left = limit * 60 - dayUsedSeconds()
        return if (left > 0) left else 0
    }

    /** -1 表示不限 */
    fun sessionRemainingSeconds(): Int {
        val limit = Config.singleMinutes
        if (limit <= 0) return -1
        val left = limit * 60 - sessionSeconds
        return if (left > 0) left else 0
    }

    fun shouldCount(): Boolean {
        if (!Config.locked || !Config.limitEnabled) return false
        if (!watching || !screenOn) return false
        if (Config.dayBlocked) return false
        if (restRemainingSeconds() > 0) return false
        return true
    }

    // ---------------- 每秒一次 ----------------

    fun tick(): TickResult {
        val now = System.currentTimeMillis()
        if (Config.restUntil in 1 until now) {
            // 休息时间到，自动解除
            Config.restUntil = 0
            sessionSeconds = 0
        }

        if (!shouldCount()) {
            if (pending > 0) flush()
            return TickResult.NONE
        }

        loadUsed()
        usedCache++
        sessionSeconds++
        pending++
        if (pending >= FLUSH_TICKS) flush()

        val single = Config.singleMinutes
        if (single > 0 && sessionSeconds >= single * 60 && startRest()) {
            return TickResult.REST
        }
        if (Config.dailyMinutes > 0 && dayRemainingSeconds() == 0) {
            Config.dayBlocked = true
            flush()
            return TickResult.DAY_OVER
        }
        return TickResult.NONE
    }

    // ---------------- 家长操作 ----------------

    /** 今天再给 n 分钟：把已用时间往回拨 */
    fun grantMinutes(minutes: Int) {
        loadUsed()
        usedCache = (usedCache - minutes * 60).coerceAtLeast(0)
        Config.dayBlocked = false
        flush()
    }

    fun resetToday() {
        usedCache = 0
        usedDate = todayKey()
        Config.dayBlocked = false
        Config.restUntil = 0
        sessionSeconds = 0
        pending = 0
        Config.usedSeconds = 0
    }

    fun flush() {
        if (pending <= 0) return
        pending = 0
        Config.usedSeconds = usedCache
    }

    private fun loadUsed() {
        val key = todayKey()
        if (usedCache < 0 || usedDate != key) {
            usedDate = key
            usedCache = Config.usedSeconds
        }
    }
}
