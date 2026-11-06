package com.kidstv.kiosk

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.kidstv.kiosk.databinding.ActivityKeyDiagBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按键诊断（隐藏页）：密码框里的「按键诊断」或家长菜单都能进。
 *
 * 为什么要有这一页：「小米电视长按菜单键没反应」这类问题，光看文字永远定不了位 ——
 * 是遥控器根本没发菜单键？发了但码不是 82？还是发了却被固件在 500ms 处截走、只留一个 UP？
 * 把品牌/型号/固件/SDK 和最近几个原始按键事件摆在一屏上，家长拍张照就够定位了。
 *
 * 这一页不预设任何键码。KEYCODE_MENU 恒等于 82，但固件想发 256、84 或自定义码都行，
 * 所以只负责"如实显示收到了什么"。
 */
class KeyDiagActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "KioskKey"

        /** 540dp 的高度只放得下 8 行，多了一条就会被挤出屏幕 */
        private const val MAX_ROWS = 8
    }

    private lateinit var b: ActivityKeyDiagBinding

    /** 最新的在最前面 */
    private val rows = ArrayList<String>()
    private val downAt = HashMap<Int, Long>()
    private val holdMax = HashMap<Int, Long>()
    private val pressCount = HashMap<Int, Int>()
    private var lastEventTime = 0L
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        KioskTheme.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityKeyDiagBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.infoText.text = deviceInfo()
        b.btnExit.setOnClickListener { finish() }
        b.btnExit.requestFocus()
        render()
    }

    /**
     * 必须在 dispatchKeyEvent 里记，不能放 onKeyDown：
     * 焦点在「返回」按钮上时，方向键和 OK 会被控件自己吃掉，onKeyDown 根本收不到 ——
     * 而这一页要的恰恰是"遥控器到底发了什么"，一条都不能漏。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        record(event)
        // 吞掉菜单/设置键：不然 AppCompat 会把 MENU 当成"打开选项菜单"，把页面盖掉
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 采集 ----------------

    private fun record(e: KeyEvent) {
        val gap = if (lastEventTime > 0L) (e.eventTime - lastEventTime).coerceAtLeast(0L) else 0L
        lastEventTime = e.eventTime

        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            pressCount[e.keyCode] = (pressCount[e.keyCode] ?: 0) + 1
            downAt[e.keyCode] = e.eventTime
        }
        if (e.action == KeyEvent.ACTION_UP) {
            downAt.remove(e.keyCode)?.let { start ->
                val hold = (e.eventTime - start).coerceAtLeast(0L)
                holdMax[e.keyCode] = maxOf(holdMax[e.keyCode] ?: 0L, hold)
            }
        }

        val arrow = when (e.action) {
            KeyEvent.ACTION_DOWN -> "↓"
            KeyEvent.ACTION_UP -> "↑"
            else -> "·"
        }
        val line = buildString {
            append(clockFormat.format(Date(wallClock(e.eventTime))))
            append(' ')
            append(arrow)
            append(' ')
            append(name(e.keyCode))
            append(" r").append(e.repeatCount)
            if (gap > 0L) append(" +").append(gap).append("ms")
            if (e.flags and KeyEvent.FLAG_LONG_PRESS != 0) append(" LONG")
            if (e.flags and KeyEvent.FLAG_CANCELED != 0) append(" CN")
            append(" sc").append(e.scanCode)
            append(" dev").append(e.deviceId)
        }
        rows.add(0, line)
        while (rows.size > MAX_ROWS) rows.removeAt(rows.size - 1)

        Log.d(TAG, line)
        render()
    }

    // ---------------- 界面 ----------------

    private fun render() {
        b.emptyHint.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.keyLog.text = rows.joinToString("\n")
        b.summary.text = summary()
    }

    /**
     * 这一行是拍给维护者看的结论。
     *
     * 家长按住菜单键 3 秒，这里却只有 480ms —— 一眼就能确定"固件在中途把按键截走了"，
     * 不用再隔着屏幕猜。反过来，如果连一次 MENU 都没收到，那就是码不对或被吃得更靠前。
     */
    private fun summary(): String {
        if (pressCount.isEmpty()) return ""
        val lines = mutableListOf<String>()
        for (k in pressCount.keys.sorted()) {
            val max = holdMax[k] ?: 0L
            val tail =
                if (max in 1L until Config.UNLOCK_LONG_PRESS_MS) "（没到 2 秒 · 可能被系统截走）"
                else ""
            lines.add("${name(k)} 按下 ${pressCount[k]} 次，最长按住 ${max}ms$tail")
        }
        if (pressCount[KeyEvent.KEYCODE_MENU] == null) {
            lines.add("MENU(82) 一次都没收到")
        }
        return lines.joinToString("\n")
    }

    private fun deviceInfo(): String {
        val pm = packageManager
        val info = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        @Suppress("DEPRECATION")
        val code = info?.versionCode ?: 0
        val stockPkg = Kiosk.stockLauncherPackage(this).ifEmpty { "没探测到" }
        return buildString {
            line("品牌", "${Build.MANUFACTURER} / ${Build.BRAND}")
            line("型号", Build.MODEL)
            line("设备", "${Build.DEVICE}（${Build.PRODUCT}）")
            line("系统", "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}")
            line("固件", Build.DISPLAY)
            line(
                "特性",
                "leanback ${mark(pm.hasSystemFeature("android.software.leanback"))}" +
                    " · device_admin ${mark(pm.hasSystemFeature("android.software.device_admin"))}"
            )
            line("桌面", "${Kiosk.stockLauncherName(this@KeyDiagActivity)}（$stockPkg）")
            line("本应用", "${info?.versionName}（code $code）")
            line("无障碍", mark(Kiosk.isWatchServiceEnabled(this@KeyDiagActivity)))
            line("强锁", "${mark(Kiosk.isDeviceOwner(this@KeyDiagActivity))} · 锁定中 ${mark(Config.kioskMode)}")
        }
    }

    /**
     * 每行都截断到一行：这一页是给手机拍照看的，
     * 换行会把下面的按键记录挤出屏幕，宁可少显示几个字。
     */
    private fun StringBuilder.line(key: String, value: String) {
        val v = if (value.length > 34) value.substring(0, 33) + "…" else value
        append(key).append("  ").append(v).append('\n')
    }

    private fun mark(has: Boolean) = if (has) "有" else "无"

    private fun name(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_") + "($keyCode)"

    /**
     * eventTime 是 uptime 基准，直接显示毫无意义。
     * 换算成墙钟：当前墙钟 - 当前 uptime + 事件 uptime，误差 1 秒内，够拍照对照。
     */
    private fun wallClock(eventTime: Long): Long =
        System.currentTimeMillis() - SystemClock.uptimeMillis() + eventTime
}
