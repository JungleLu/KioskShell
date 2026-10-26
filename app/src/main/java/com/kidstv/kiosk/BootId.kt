package com.kidstv.kiosk

import android.os.SystemClock
import java.io.File

/**
 * 「这一次开机是谁」。
 *
 * 用来判断某个上次记下的状态是不是**上一轮开机**留下的，语义就是"重启即作废"
 * （比如家长按的「本次放行」）。
 *
 * 为什么不靠开机广播：
 * 1. 部分电视固件压根不发 `BOOT_COMPLETED`（海信快速开机实测过）；
 * 2. 就算发了，广播结束后进程可能立刻被系统回收，`SharedPreferences.apply()` 的
 *    异步写盘还没落地就被丢掉 —— 于是"开机清放行"这一步静默失效。
 *
 * `/proc/sys/kernel/random/boot_id` 是内核每次开机重新生成的 UUID，普通应用可直接读，
 * 比任何广播都可靠。读不到时（极少数固件关了 procfs）退回"uptime 变小 = 重启过"。
 */
object BootId {

    /** 本次开机的 ID；读不到时为空串。进程活不过一次开机，所以缓存是安全的 */
    val current: String by lazy {
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从「记录时刻（当时的 uptime 毫秒）+ 当时的 boot_id」到现在，是否已经重启过。
     *
     * @param recordedAt 记录时的 `SystemClock.elapsedRealtime()`；0 表示没记过
     * @param recordedId 记录时的 [current]；空串表示当时没读到
     */
    fun sinceReboot(recordedAt: Long, recordedId: String): Boolean {
        if (recordedAt <= 0L) return true                    // 没记 → 无从判断，按重启过处理
        if (recordedId.isNotEmpty() && current.isNotEmpty()) return recordedId != current
        return SystemClock.elapsedRealtime() < recordedAt    // uptime 倒退 = 重启过
    }
}
