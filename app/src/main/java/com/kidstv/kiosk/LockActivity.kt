package com.kidstv.kiosk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kidstv.kiosk.databinding.ActivityLockBinding

/**
 * 到点后盖在最上面的锁屏页。两种形态：
 *  - MODE_REST：休息倒计时，到点自动解开
 *  - MODE_DAY：今天的额度用完了，只能等明天或家长手动加时
 *
 * 返回键无效。遥控器「菜单键 / 设置键」长按 2 秒 + 密码才能动。
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val MODE_REST = "rest"
        const val MODE_DAY = "day"
        const val EXTRA_MODE = "mode"

        fun open(ctx: Context, mode: String) {
            val intent = Intent(ctx, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_MODE, mode)
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
            }
        }
    }

    private lateinit var binding: ActivityLockBinding
    private var mode = MODE_REST

    private val handler = Handler(Looper.getMainLooper())
    private var unlockKeyHeld = false
    private var pinShowing = false

    private val unlockRunnable = Runnable {
        unlockKeyHeld = false
        if (!pinShowing) askPin()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        KioskTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_REST
        refresh()
        speak()
        handler.postDelayed(ticker, 1000L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_MODE)?.let { mode = it }
        refresh()
        speak()
    }

    /**
     * 到点说一句。Voice 内部按锚点去重：
     * GuardService 每 8 秒就会把这一页重新拉起来一次，不去重就会一直重复念。
     */
    private fun speak() {
        if (mode == MODE_REST) {
            Voice.playRestOnce(this)
        } else {
            Voice.playDayOnce(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(unlockRunnable)
        Voice.release()
        super.onDestroy()
    }

    // ---------------- 界面 ----------------

    private fun refresh() {
        if (mode == MODE_REST) {
            val left = Timer.restRemainingSeconds()
            if (left <= 0) {
                Timer.onRestDone()
                finish()
                return
            }
            binding.titleText.text = "休息一下"
            binding.bigText.text = formatClock(left)
            binding.subText.text = "看看远处，让眼睛休息一下\n到点自动继续"
            binding.hintText.text = "长按菜单键 2 秒可提前结束"
        } else {
            val used = Timer.dayUsedSeconds() / 60
            val limit = Config.dailyMinutes
            binding.titleText.text = "今天的时间用完了"
            binding.bigText.text = "明天再来"
            binding.subText.text = "今天已经看了 $used 分钟" +
                if (limit > 0) " · 每天 $limit 分钟" else ""
            binding.hintText.text = "长按菜单键 2 秒家长可加时"
        }
    }

    private fun formatClock(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    // ---------------- 按键 ----------------

    private fun isUnlockKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isUnlockKey(keyCode)) {
            if (!unlockKeyHeld) {
                unlockKeyHeld = true
                handler.postDelayed(unlockRunnable, 2000L)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isUnlockKey(keyCode)) {
            unlockKeyHeld = false
            handler.removeCallbacks(unlockRunnable)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        // 返回键失效
    }

    // ---------------- 家长操作 ----------------

    private fun askPin() {
        pinShowing = true
        PinDialog.show(
            activity = this,
            title = "家长验证",
            onDismiss = { pinShowing = false }
        ) {
            pinShowing = false
            showParentOptions()
        }
    }

    private fun showParentOptions() {
        if (mode == MODE_REST) {
            val items = arrayOf(
                "结束这次休息，继续看",
                "关闭时长管控（今天不再打断）"
            )
            AlertDialog.Builder(this)
                .setTitle("家长操作")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            Timer.endRestEarly()
                            finish()
                        }
                        1 -> {
                            Config.limitEnabled = false
                            Timer.endRestEarly()
                            Toast.makeText(this, "时长管控已关，长按菜单键可重开", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        } else {
            val items = arrayOf(
                "今天再给 30 分钟",
                "今天再给 60 分钟",
                "今天到此为止"
            )
            AlertDialog.Builder(this)
                .setTitle("家长操作")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            Timer.grantMinutes(30)
                            finish()
                        }
                        1 -> {
                            Timer.grantMinutes(60)
                            finish()
                        }
                        else -> finish()
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }
}
