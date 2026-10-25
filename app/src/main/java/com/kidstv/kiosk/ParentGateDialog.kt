package com.kidstv.kiosk

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.kidstv.kiosk.databinding.DialogParentGateBinding

/**
 * 家长门禁弹窗：自绘数字键盘 + 每次随机出题 + 答错换题 + 3 次错锁 60 秒。
 *
 * 为什么不用 EditText：电视上 EditText 一获得焦点就走 InputMethodManager，
 * Leanback 输入法是整屏覆盖，会把弹窗盖掉；部分海信/创维固件干脆没有 IME，
 * 表现为"按 OK 没反应"。所以键盘必须自己画。
 */
object ParentGateDialog {

    private const val MAX_FAILS = 3
    private const val COOL_MS = 60_000L

    fun show(
        activity: Activity,
        title: String = "家长验证",
        onDismiss: () -> Unit = {},
        onOk: () -> Unit
    ) {
        val b = DialogParentGateBinding.inflate(LayoutInflater.from(activity))
        val mode = Challenge.currentMode()
        var question = Challenge.current(mode)
        var input = StringBuilder()
        var cooling = false

        val handler = Handler(Looper.getMainLooper())
        val dialog = AlertDialog.Builder(activity)
            .setView(b.root)
            .create()

        b.gateTitle.text = title
        b.gatePrompt.text = question.prompt

        fun renderInput() {
            b.gateInput.text = if (mode == Challenge.Mode.PIN) {
                "•".repeat(input.length)
            } else {
                input.toString()
            }
        }

        fun setKeysEnabled(enabled: Boolean) {
            val keys = listOf(
                b.k1, b.k2, b.k3, b.k4, b.k5, b.k6,
                b.k7, b.k8, b.k9, b.kDel, b.k0, b.kOk
            )
            for (k in keys) {
                k.isEnabled = enabled
                k.alpha = if (enabled) 1f else 0.35f
            }
            if (!enabled) {
                b.gateCancel.requestFocus()
            }
        }

        // 冷却倒计时（进程被杀也要认账，值存在 SharedPreferences 里）
        var coolTicker: Runnable? = null
        fun startCooldown(remainMs: Long) {
            cooling = true
            setKeysEnabled(false)
            var left = remainMs
            coolTicker = object : Runnable {
                override fun run() {
                    if (left <= 0) {
                        cooling = false
                        Config.gateCoolUntil = 0
                        b.gateError.text = ""
                        setKeysEnabled(true)
                        b.k5.requestFocus()
                        return
                    }
                    b.gateError.text = "错太多次，${left / 1000} 秒后再试"
                    left -= 1000
                    handler.postDelayed(this, 1000L)
                }
            }
            handler.post(coolTicker!!)
        }

        // 纯数字档要能输满 8 位（默认密码就是 6 位）；数学题答案最多 3 位，留到 4 位。
        val maxLen = if (mode == Challenge.Mode.PIN) 8 else 4

        fun append(ch: Char) {
            if (cooling) return
            if (input.length >= maxLen) return
            input.append(ch)
            renderInput()
        }

        fun backspace() {
            if (cooling || input.isEmpty()) return
            input.deleteCharAt(input.length - 1)
            renderInput()
        }

        fun submit() {
            if (cooling) return
            if (input.isEmpty()) return
            if (input.toString() == question.answer) {
                Config.gateFails = 0
                Config.gateCoolUntil = 0
                Config.clearChallenge()
                handler.removeCallbacksAndMessages(null)
                dialog.dismiss()
                onOk()
                return
            }

            // 答错：换一道新题，累计 3 次锁 60 秒
            val fails = Config.gateFails + 1
            if (fails >= MAX_FAILS) {
                Config.gateFails = 0
                Config.gateCoolUntil = SystemClock.elapsedRealtime() + COOL_MS
                question = Challenge.refresh(mode)
                b.gatePrompt.text = question.prompt
                input = StringBuilder()
                renderInput()
                startCooldown(COOL_MS)
            } else {
                Config.gateFails = fails
                b.gateError.text =
                    if (mode == Challenge.Mode.PIN) "密码不对" else "答案不对，再算一次"
                question = Challenge.refresh(mode)
                b.gatePrompt.text = question.prompt
                input = StringBuilder()
                renderInput()
            }
        }

        b.k0.setOnClickListener { append('0') }
        b.k1.setOnClickListener { append('1') }
        b.k2.setOnClickListener { append('2') }
        b.k3.setOnClickListener { append('3') }
        b.k4.setOnClickListener { append('4') }
        b.k5.setOnClickListener { append('5') }
        b.k6.setOnClickListener { append('6') }
        b.k7.setOnClickListener { append('7') }
        b.k8.setOnClickListener { append('8') }
        b.k9.setOnClickListener { append('9') }
        b.kDel.setOnClickListener { backspace() }
        b.kOk.setOnClickListener { submit() }
        b.gateCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            onDismiss()
        }
        dialog.setCanceledOnTouchOutside(false)

        // 硬件数字键兜底。绝不拦 DPAD_CENTER / ENTER —— 会和按键自身的点击叠加成两次
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { append('0'); true }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { append('1'); true }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { append('2'); true }
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { append('3'); true }
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { append('4'); true }
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> { append('5'); true }
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> { append('6'); true }
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> { append('7'); true }
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> { append('8'); true }
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> { append('9'); true }
                KeyEvent.KEYCODE_DEL -> { backspace(); true }
                else -> false
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            sizeToScreen(dialog, activity)
            b.root.post { b.k5.requestFocus() }
            val remain = cooldownRemaining()
            if (remain > 0) startCooldown(remain)
        }

        dialog.show()
    }

    /**
     * 单纯输入一串数字（改家长密码用）。
     * 同样走自绘键盘 —— 电视上没有系统键盘可用，EditText 在这里等于死路。
     */
    fun inputNumber(
        activity: Activity,
        title: String,
        prompt: String,
        maxLen: Int = 8,
        onOk: (String) -> Unit
    ) {
        val b = DialogParentGateBinding.inflate(LayoutInflater.from(activity))
        var input = StringBuilder()
        val dialog = AlertDialog.Builder(activity).setView(b.root).create()

        b.gateTitle.text = title
        b.gatePrompt.text = prompt
        b.gatePrompt.textSize = 24f
        b.gateError.text = ""

        fun render() {
            b.gateInput.text = "•".repeat(input.length)
        }

        fun append(ch: Char) {
            if (input.length >= maxLen) return
            b.gateError.text = ""
            input.append(ch)
            render()
        }

        fun backspace() {
            if (input.isEmpty()) return
            input.deleteCharAt(input.length - 1)
            render()
        }

        b.k0.setOnClickListener { append('0') }
        b.k1.setOnClickListener { append('1') }
        b.k2.setOnClickListener { append('2') }
        b.k3.setOnClickListener { append('3') }
        b.k4.setOnClickListener { append('4') }
        b.k5.setOnClickListener { append('5') }
        b.k6.setOnClickListener { append('6') }
        b.k7.setOnClickListener { append('7') }
        b.k8.setOnClickListener { append('8') }
        b.k9.setOnClickListener { append('9') }
        b.kDel.setOnClickListener { backspace() }
        b.kOk.setOnClickListener {
            if (input.isEmpty()) {
                b.gateError.text = "还没输入"
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(input.toString())
        }
        b.gateCancel.setOnClickListener { dialog.dismiss() }

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { append('0'); true }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { append('1'); true }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { append('2'); true }
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { append('3'); true }
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { append('4'); true }
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> { append('5'); true }
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> { append('6'); true }
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> { append('7'); true }
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> { append('8'); true }
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> { append('9'); true }
                KeyEvent.KEYCODE_DEL -> { backspace(); true }
                else -> false
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            sizeToScreen(dialog, activity)
            b.root.post { b.k5.requestFocus() }
        }
        dialog.show()
    }

    /**
     * 按屏幕像素算窗口尺寸。
     *
     * 1920×1080 + 密度 320 的电视只有 540dp 可用高度，主题里写百分比（windowMinWidth*）
     * 会和内容自身的 wrap_content 打架，出现过"只显示一行键盘、其余被挤出屏幕"。
     * 所以宽度取屏幕的一半，高度交给内容自己决定，超了由布局里的 ScrollView 兜。
     */
    private fun sizeToScreen(dialog: AlertDialog, activity: Activity) {
        val dm = activity.resources.displayMetrics
        val width = (dm.widthPixels * 0.50f).toInt().coerceAtLeast(480)
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /**
     * 剩下的冷却时间。用 elapsedRealtime 计时，改系统日期绕不过去；
     * 电视重启后 elapsed 归零，剩余时间会被算得偏大，所以夹到 60 秒封顶。
     */
    private fun cooldownRemaining(): Long {
        val until = Config.gateCoolUntil
        if (until <= 0L) return 0L
        val left = until - SystemClock.elapsedRealtime()
        return left.coerceIn(0L, COOL_MS)
    }
}
