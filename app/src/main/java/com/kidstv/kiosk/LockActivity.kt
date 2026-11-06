package com.kidstv.kiosk

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    /** 答题区的四个选项，顺序就是布局里的 opt0..opt3 */
    private val optionViews by lazy {
        listOf(binding.opt0, binding.opt1, binding.opt2, binding.opt3)
    }

    /** 当前这道题。null = 该出新题了 */
    private var item: Quiz.Item? = null

    /** 已经作答，等动画走完 —— 防孩子连按 OK 把四个选项全点一遍 */
    private var answered = false

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

        // 左下角「家长」：和首页同一个入口。没有它，收不到菜单键的电视在这页就只能干等到点
        binding.parentBtn.setOnClickListener { if (!pinShowing) askPin() }

        setupQuiz()

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
        // 清掉全部延迟任务：答对后 0.9 秒要 finish、答错后 1.6 秒要换题，
        // 万一这期间页面被系统销毁，回调里再碰 binding 就会崩
        handler.removeCallbacksAndMessages(null)
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
            refreshQuiz()
            binding.hintText.text = "长按菜单键或点左下角「家长」，可提前结束"
        } else {
            val used = Timer.dayUsedSeconds() / 60
            val limit = Config.dailyMinutes
            binding.titleText.text = "今天的时间用完了"
            binding.bigText.text = "明天再来"
            // 「今天额度用完」是硬上限，不该被做题突破 —— 这一页永远不出题
            binding.quizPanel.visibility = View.GONE
            item = null
            binding.subText.visibility = View.VISIBLE
            binding.bigText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
            binding.subText.text = "今天已经看了 $used 分钟" +
                if (limit > 0) " · 每天 $limit 分钟" else ""
            binding.hintText.text = "长按菜单键或点左下角「家长」，家长可加时"
        }
    }

    /**
     * 答题区。三个条件全满足才出现：家长开着、今天还有次数、这一次休息的两回机会还没用完。
     *
     * 答题时把倒计时从 92sp 压到 44sp：1080p 上一屏放不下"大字倒计时 + 四宫格选项"，
     * 硬塞会把题目挤出屏幕，而题目才是这一页现在的主角。
     */
    private fun refreshQuiz() {
        val wrong = Config.quizRestWrong
        val on = Quiz.available() && wrong < Quiz.MAX_TRIES
        binding.quizPanel.visibility = if (on) View.VISIBLE else View.GONE

        if (!on) {
            item = null
            answered = false
            binding.subText.visibility = View.VISIBLE
            binding.bigText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
            binding.subText.text =
                if (Quiz.available() && wrong >= Quiz.MAX_TRIES) {
                    // 说清"为什么没题了"，否则孩子会以为是坏了
                    "这次的机会用完了\n到点自动继续"
                } else {
                    "看看远处，让眼睛休息一下\n到点自动继续"
                }
            return
        }

        binding.subText.visibility = View.GONE
        binding.bigText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        if (item == null) {
            item = Quiz.next()
            answered = false
            renderQuestion()
        }
    }

    private fun renderQuestion() {
        val q = item ?: return
        binding.quizTag.text = "${Quiz.gradeLabel(Config.quizGrade)} · 数学"
        binding.quizPrompt.text = q.prompt
        binding.quizFoot.text = "答对可以提前继续 · 今天还能答 ${Quiz.remainingToday()} 次"

        optionViews.forEachIndexed { i, v ->
            v.text = q.options[i]
            v.isEnabled = true
            v.setTextColor(colorOf(R.attr.kioskText))
            val tv = TypedValue()
            theme.resolveAttribute(R.attr.kioskKey, tv, true)
            if (tv.resourceId != 0) v.setBackgroundResource(tv.resourceId)
        }

        // 遥控器直接按 OK 就能答，不用先摸索焦点在哪
        binding.quizPanel.post { optionViews.first().requestFocus() }
    }

    private fun setupQuiz() {
        optionViews.forEachIndexed { index, v ->
            v.setOnClickListener { onOption(index) }
        }
    }

    private fun onOption(index: Int) {
        val q = item ?: return
        if (answered) return
        answered = true

        val right = optionViews[index].text.toString() == q.answer

        // 答错时也要把正确答案标绿：不标的话孩子不知道为什么错、也没法学
        optionViews.forEachIndexed { i, v ->
            v.isEnabled = false
            val text = v.text.toString()
            if (text == q.answer) {
                v.setBackgroundResource(R.drawable.bg_quiz_ok)
                v.setTextColor(Color.WHITE)
            } else if (i == index) {
                v.setBackgroundResource(R.drawable.bg_quiz_bad)
                v.setTextColor(Color.WHITE)
            }
        }

        if (right) {
            Config.quizUsedToday = Config.quizUsedToday + 1
            binding.quizFoot.text = "答对啦，继续看"
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                // endRestEarly 已经把 sessionSeconds 归零，所以回去后要再满一整个
                // 单次时长才会再休息 —— 不用额外写
                Timer.endRestEarly()
                finish()
            }, 900L)
        } else {
            Config.quizRestWrong = Config.quizRestWrong + 1
            val left = (Quiz.MAX_TRIES - Config.quizRestWrong).coerceAtLeast(0)
            binding.quizFoot.text = if (left > 0) "再想想，还有 $left 次机会" else "这次机会用完了"
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                item = null
                refresh()
            }, 1600L)
        }
    }

    private fun colorOf(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
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
                handler.postDelayed(unlockRunnable, Config.UNLOCK_LONG_PRESS_MS)
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
