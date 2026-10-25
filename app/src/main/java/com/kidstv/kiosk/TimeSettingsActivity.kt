package com.kidstv.kiosk

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.kidstv.kiosk.databinding.ActivityTimeSettingsBinding
import com.kidstv.kiosk.databinding.ItemTimeRowBinding

/**
 * 观看时长设置页。
 *
 * 用左右键改数值而不是「点一下跳一档」：电视上一堆选项滚起来会让人失去耐心，
 * 左右加减更接近遥控器的心智。改完立即写 Config，Timer 下一秒就按新参数判定。
 */
class TimeSettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityTimeSettingsBinding

    private val singleOptions = intArrayOf(0, 10, 15, 20, 25, 30, 40, 45, 60)
    private val restOptions = intArrayOf(0, 1, 3, 5, 10, 15, 20, 30)
    private val dailyOptions = intArrayOf(0, 30, 45, 60, 90, 120, 150, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        KioskTheme.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityTimeSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindStepRow(b.rowSingle) { dir ->
            Config.singleMinutes = shift(singleOptions, Config.singleMinutes, dir)
        }
        bindStepRow(b.rowRest) { dir ->
            Config.restMinutes = shift(restOptions, Config.restMinutes, dir)
        }
        bindStepRow(b.rowDaily) { dir ->
            Config.dailyMinutes = shift(dailyOptions, Config.dailyMinutes, dir)
        }
        bindStepRow(b.rowGate) { dir ->
            Config.gateMode = shiftMode(Config.gateMode, dir).key
        }
        bindStepRow(b.rowAuto) {
            Config.autoLaunch = !Config.autoLaunch
        }
        bindStepRow(b.rowVoice) {
            Config.voiceEnabled = !Config.voiceEnabled
        }

        b.rowPreview.root.setOnClickListener { Voice.preview(this) }
        b.rowReset.root.setOnClickListener {
            Timer.resetToday()
            render()
        }
        b.rowDefault.root.setOnClickListener {
            Config.singleMinutes = 25
            Config.restMinutes = 5
            Config.dailyMinutes = 60
            Config.limitEnabled = true
            Config.gateMode = "pin"
            Config.voiceEnabled = true
            render()
        }
        b.btnBack.setOnClickListener { finish() }

        render()
        b.rowSingle.root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        Voice.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- 行绑定 ----------------

    /** 左右键改值，OK 键也当成「加一档」 */
    private fun bindStepRow(row: ItemTimeRowBinding, change: (Int) -> Unit) {
        row.root.setOnKeyListener(object : View.OnKeyListener {
            override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
                if (event?.action != KeyEvent.ACTION_DOWN) return false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        change(-1)
                        render()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        change(1)
                        render()
                        return true
                    }
                }
                return false
            }
        })
        row.root.setOnClickListener {
            change(1)
            render()
        }
    }

    private fun render() {
        b.rowSingle.rowLabel.text = getString(R.string.row_single)
        b.rowSingle.rowValue.text = minutesText(Config.singleMinutes, 0)
        b.rowSingle.rowHint.text = "◀ ▶"

        b.rowRest.rowLabel.text = getString(R.string.row_rest)
        b.rowRest.rowValue.text = minutesText(Config.restMinutes, 1)
        b.rowRest.rowHint.text = "◀ ▶"

        b.rowDaily.rowLabel.text = getString(R.string.row_daily)
        b.rowDaily.rowValue.text = minutesText(Config.dailyMinutes, 0)
        b.rowDaily.rowHint.text = "◀ ▶"

        b.rowGate.rowLabel.text = getString(R.string.row_gate)
        b.rowGate.rowValue.text = Challenge.Mode.of(Config.gateMode).label
        b.rowGate.rowHint.text = "◀ ▶"

        b.rowAuto.rowLabel.text = getString(R.string.row_auto)
        b.rowAuto.rowValue.text =
            getString(if (Config.autoLaunch) R.string.value_on else R.string.value_off)
        b.rowAuto.rowHint.text = "◀ ▶"

        b.rowVoice.rowLabel.text = getString(R.string.row_voice)
        b.rowVoice.rowValue.text =
            getString(if (Config.voiceEnabled) R.string.value_on else R.string.value_off)

        b.rowPreview.rowLabel.text = getString(R.string.row_preview)
        b.rowPreview.rowValue.text = ""
        b.rowPreview.rowHint.text = getString(R.string.row_preview_hint)

        b.rowReset.rowLabel.text = getString(R.string.row_reset, Timer.dayUsedSeconds() / 60)
        b.rowReset.rowValue.text = ""
        b.rowReset.rowHint.text = getString(R.string.row_reset_hint)

        b.rowDefault.rowLabel.text = getString(R.string.row_default)
        b.rowDefault.rowValue.text = ""
        b.rowDefault.rowHint.text = getString(R.string.row_default_hint)
    }

    private fun minutesText(value: Int, zeroMeans: Int): String {
        if (value <= 0) {
            return getString(
                if (zeroMeans == 1) R.string.value_no_rest else R.string.value_unlimited
            )
        }
        return getString(R.string.minutes, value)
    }

    private fun shift(options: IntArray, current: Int, dir: Int): Int {
        val index = options.indexOfFirst { it == current }
        if (index < 0) return options.firstOrNull { it > current } ?: options.last()
        val next = (index + dir).coerceIn(0, options.size - 1)
        return options[next]
    }

    private fun shiftMode(current: String, dir: Int): Challenge.Mode {
        val modes = Challenge.Mode.values()
        val index = modes.indexOfFirst { it.key == current }
        val start = if (index < 0) 0 else index
        return modes[(start + dir + modes.size) % modes.size]
    }
}
