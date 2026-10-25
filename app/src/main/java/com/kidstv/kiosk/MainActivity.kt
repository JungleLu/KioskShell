package com.kidstv.kiosk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kidstv.kiosk.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val handler = Handler(Looper.getMainLooper())
    private var unlockKeyHeld = false
    private var pinDialogShowing = false

    /** 家长动过菜单/门禁，这一轮就别再自动跳应用了 */
    private var suppressAuto = false

    /**
     * 自动进入目标应用前的宽限时间。
     * 关键不在长短，而在「按任意键就取消自动跳」——家长动一下遥控器就有足够时间长按菜单。
     */
    private val autoLaunchDelay = 4000L

    companion object {
        /** 家长进系统设置 / 无障碍设置时，看门狗暂停多久 */
        private const val WATCH_PAUSE_MS = 10 * 60 * 1000L
    }

    private val autoLaunchRunnable = Runnable {
        if (pinDialogShowing || suppressAuto) return@Runnable
        if (!Config.autoLaunch) return@Runnable
        if (Timer.restRemainingSeconds() > 0 || Config.dayBlocked) return@Runnable
        if (!anyTargetInstalled()) return@Runnable
        launchTarget()
    }

    private val unlockRunnable = Runnable {
        unlockKeyHeld = false
        if (!pinDialogShowing) showPinDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题必须在 super.onCreate 之前设，否则窗口背景用的是清单里的默认皮肤
        KioskTheme.apply(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 首页右下角的主题切换：点一下循环换肤。纯外观，不碰锁定状态
        binding.themeBtn.setOnClickListener { toggleTheme() }
        refreshThemeBtn()
        refreshTarget()

        if (Config.firstRun) {
            Toast.makeText(this, "默认密码 123456，长按菜单键可改", Toast.LENGTH_LONG).show()
            Config.firstRun = false
        }

        // 计时服务常驻：桌面在前台时不计数，进了别的应用才计
        GuardService.start(this)
    }

    override fun onResume() {
        super.onResume()

        // 重新打开儿童桌面 = 结束「本次放行」。家长从聚好看点图标回来，就自动恢复锁定，
        // 不用再进菜单点一次。（打开放行按钮时是往 HOME 走，不会回到这里，所以不会误清）
        if (Config.passOnce) {
            Config.passOnce = false
            Toast.makeText(this, "已结束放行，恢复锁定", Toast.LENGTH_SHORT).show()
        }

        Timer.setWatching(false)
        refreshTarget()
        refreshTimeInfo()

        // 悬浮窗权限被系统无声收回过（海信固件实测会这么干）：回桌面立刻弹引导，
        // 不然到点盖不出休息页、开机拉不起界面，家长还不知道为什么
        if (Config.overlayLost) {
            Config.overlayLost = false
            requestOverlayPermission()
        }
        refreshThemeBtn()

        // Device Owner 下每次回桌面都重申一遍：HOME 归属 + 白名单（有些固件会自己改回去）
        if (Kiosk.isDeviceOwner(this)) {
            Kiosk.applyPolicy(this)
            if (Config.lockTask && !Kiosk.isLockTaskActive(this)) {
                Kiosk.enter(this)
            }
        }

        // 孩子按 HOME 躲开休息锁屏，回到桌面也要挡回去
        when {
            Timer.restRemainingSeconds() > 0 -> LockActivity.open(this, LockActivity.MODE_REST)
            Config.dayBlocked -> LockActivity.open(this, LockActivity.MODE_DAY)
            else -> scheduleAutoLaunch()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(autoLaunchRunnable)
        Timer.setWatching(true)
        super.onPause()
    }

    override fun onStop() {
        // 离开桌面（去目标应用 / 去系统设置）就重新武装，回来时还能自动跳
        suppressAuto = false
        super.onStop()
    }

    /**
     * 选好目标应用后，桌面只做中转：停留几秒就自动进应用（默认关）。
     *
     * 为什么要留这几秒：家长进家长菜单要长按 2 秒，如果桌面一到就跳走，
     * 家长永远没有机会按。孩子按 OK 会立刻进（点应用卡片），不用等。
     */
    private fun scheduleAutoLaunch() {
        handler.removeCallbacks(autoLaunchRunnable)
        if (!Config.autoLaunch || suppressAuto) return
        if (!anyTargetInstalled()) return
        handler.postDelayed(autoLaunchRunnable, autoLaunchDelay)
    }

    // ---------- 按键拦截 ----------

    private fun isUnlockKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 家长按一下遥控器（方向键/菜单键等）就取消这次自动跳转，慢慢长按即可。
        // 但不能把 HOME / 返回 / 音量 / OK 算进去 —— 按 HOME 回桌面后要能继续自动进应用，
        // 否则「回家 → 4 秒又进去」这个环就断了。
        if (!isNonCancelKey(keyCode)) {
            cancelAutoLaunch()
        }
        if (isUnlockKey(keyCode)) {
            if (!unlockKeyHeld) {
                unlockKeyHeld = true
                handler.postDelayed(unlockRunnable, 2000L)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isNonCancelKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    /** 取消自动跳转（本次停留在桌面，直到离开桌面再回来） */
    private fun cancelAutoLaunch() {
        handler.removeCallbacks(autoLaunchRunnable)
        suppressAuto = true
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
        // 返回键失效，孩子退不出去
    }

    override fun onDestroy() {
        handler.removeCallbacks(unlockRunnable)
        handler.removeCallbacks(autoLaunchRunnable)
        super.onDestroy()
    }

    // ---------- 界面 ----------

    /**
     * 渲染目标应用卡片行。
     *
     * 多选之后的桌面：勾了几个就排几张卡，点哪张进哪个应用。
     * 三态：还没选 / 选的应用全卸载了 / 正常（正常态只显示还装着的那些）。
     */
    private fun refreshTarget() {
        val pm = packageManager
        val row = binding.appsRow
        row.removeAllViews()

        // 还装着的目标应用（按家长勾选的顺序）
        val installed = Config.targets.filter { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        if (Config.targets.isEmpty()) {
            row.addView(buildGuideCard(getString(R.string.no_target_title)))
            binding.hintText.text = getString(R.string.no_target_hint)
            return
        }

        if (installed.isEmpty()) {
            val gone = Config.targets.first()
            row.addView(buildGuideCard("${appLabelOrPkg(gone)} 已卸载"))
            binding.hintText.text = getString(R.string.target_missing_hint)
            return
        }

        // 按数量分档：1 个大卡 / 2 个中卡 / 3 个及以上小卡
        val (cardW, cardH, iconSize, nameSize, pad) = when (installed.size) {
            1 -> Quint(300, 320, 150, 24f, 26)
            2 -> Quint(200, 240, 100, 18f, 20)
            else -> Quint(150, 185, 76, 14f, 14)
        }

        installed.forEachIndexed { i, pkg ->
            val info = pm.getApplicationInfo(pkg, 0)
            val card = buildAppCard(
                pkg = pkg,
                label = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info),
                cardW = cardW,
                cardH = cardH,
                iconSize = iconSize,
                nameSize = nameSize,
                pad = pad
            )
            // cardW/cardH 是按 dp 设计的，这里必须转 px——电视密度 320（2 倍），
            // 不转的话卡片只有一半大，名字会被图标挤出卡片（v1.22 实测的坑）
            val lp = android.widget.LinearLayout.LayoutParams(dp(cardW), dp(cardH))
            if (i > 0) lp.marginStart = dp(14)
            row.addView(card, lp)
            if (i == 0) card.requestFocus()
        }

        binding.hintText.text = statusText()

        // 目标被卸载后又装回来时白名单不会自动恢复，这里补一把
        if (Kiosk.isDeviceOwner(this)) Kiosk.applyPolicy(this)
    }

    private data class Quint(
        val cardW: Int, val cardH: Int, val iconSize: Int,
        val nameSize: Float, val pad: Int
    )

    /** 「还没选 / 已卸载」时的占位卡片：半透明，点了只提示，不打开选择列表 */
    private fun buildGuideCard(title: String): android.view.View {
        val card = android.widget.FrameLayout(this)
        card.background = themedDrawable(R.attr.kioskCard)
        card.isFocusable = true
        card.isClickable = true
        card.alpha = 0.5f
        card.contentDescription = title
        card.setOnClickListener {
            Toast.makeText(this, R.string.no_target_toast, Toast.LENGTH_LONG).show()
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(26), dp(26), dp(26), dp(26))
        }

        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(150), dp(150))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val name = android.widget.TextView(this).apply {
            text = title
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(KioskTheme.color(context, R.attr.kioskText, R.color.cream_text))
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        box.addView(icon)
        box.addView(name)
        card.addView(box)
        return card
    }

    /** 一张应用卡片：图标 + 名字。焦点框由 kioskCard 的 selector 负责 */
    private fun buildAppCard(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable?,
        cardW: Int,
        cardH: Int,
        iconSize: Int,
        nameSize: Float,
        pad: Int
    ): android.view.View {
        val card = android.widget.FrameLayout(this)
        card.background = themedDrawable(R.attr.kioskCard)
        card.isFocusable = true
        card.isClickable = true
        card.contentDescription = label
        card.setOnClickListener { onAppCardClick(pkg) }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }

        val iconView = android.widget.ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val nameView = android.widget.TextView(this).apply {
            text = label
            textSize = nameSize
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(KioskTheme.color(context, R.attr.kioskText, R.color.cream_text))
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        box.addView(iconView)
        box.addView(nameView)
        card.addView(box)
        return card
    }

    /** 按主题 attr 取 drawable（卡片背景），不写死某套皮肤 */
    private fun themedDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            ta.getDrawable(0)
        } finally {
            ta.recycle()
        }
    }

    /** 卸载后拿不到 label，只能从包名里取最后一段凑一个能认的名字 */
    private fun appLabelOrPkg(pkg: String): String {
        return pkg.substringAfterLast('.').ifEmpty { pkg }
    }

    /** 勾选的应用里有没有还装着的（自动进入、门禁文案都用这个） */
    private fun anyTargetInstalled(): Boolean {
        return Config.targets.any { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun statusText(): String {
        val base = when {
            Config.kioskMode && Config.passOnce ->
                "放行中 · 重启后恢复锁定"
            Config.kioskMode && !watchServiceOn() ->
                "看门狗没开 · 设置→无障碍→儿童桌面"
            Config.kioskMode -> "已锁定 · 长按菜单键 2 秒退出"
            Kiosk.isDeviceOwner(this) && !Config.policyOk ->
                "强锁没设置成功 · 长按菜单键 2 秒看看"
            Kiosk.isDeviceOwner(this) -> "强锁中 · 长按菜单键 2 秒退出"
            else -> "未锁定 · 长按菜单键 2 秒设置"
        }
        return if (Config.autoLaunch && anyTargetInstalled()) {
            "$base · 按任意键取消自动进入"
        } else {
            base
        }
    }

    /** 桌面上只显示额度，不显示倒计时秒数，免得孩子盯着数字看 */
    private fun refreshTimeInfo() {
        binding.timeText.text = when {
            !Config.limitEnabled -> "时长管控已关"
            Config.dayBlocked -> "今天用完了，明天再来"
            Timer.restRemainingSeconds() > 0 -> {
                val left = Timer.restRemainingSeconds()
                "休息中 · 还剩 ${left / 60} 分 ${left % 60} 秒"
            }
            else -> {
                val used = Timer.dayUsedSeconds() / 60
                val day = Config.dailyMinutes
                val single = Config.singleMinutes
                val dayPart = if (day <= 0) "每天不限" else "今天已看 $used/$day 分钟"
                val singlePart = if (single <= 0) "单次不限" else "每次最多 $single 分钟"
                "$dayPart · $singlePart"
            }
        }
    }

    private fun launchTarget() {
        if (Config.locked && Config.limitEnabled) {
            if (Config.dayBlocked) {
                LockActivity.open(this, LockActivity.MODE_DAY)
                return
            }
            if (Timer.restRemainingSeconds() > 0) {
                LockActivity.open(this, LockActivity.MODE_REST)
                return
            }
        }
        if (!Kiosk.launchTarget(this)) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 点桌面上的应用卡片（多选之后有若干张，点哪张进哪个）。
     *
     * 目标应用没选或已卸载时只给一句提示，**不打开选择列表** ——
     * 卡片是孩子最常按的按钮，从这里能改目标应用等于把门禁送出去了。
     * 所有控制类操作只留「长按菜单键 2 秒」一个入口。
     */
    private fun onAppCardClick(pkg: String) {
        if (Config.locked && Config.limitEnabled) {
            if (Config.dayBlocked) {
                LockActivity.open(this, LockActivity.MODE_DAY)
                return
            }
            if (Timer.restRemainingSeconds() > 0) {
                LockActivity.open(this, LockActivity.MODE_REST)
                return
            }
        }

        val installed = try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) {
            Toast.makeText(this, R.string.target_missing_toast, Toast.LENGTH_LONG).show()
            return
        }

        if (!Kiosk.launchTarget(this, pkg)) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 密码与家长菜单 ----------

    private fun showPinDialog() {
        pinDialogShowing = true
        suppressAuto = true
        handler.removeCallbacks(autoLaunchRunnable)
        PinDialog.show(
            activity = this,
            title = "家长验证",
            onDismiss = { pinDialogShowing = false }
        ) {
            pinDialogShowing = false
            showParentMenu()
        }
    }

    private fun showParentMenu() {
        suppressAuto = true
        handler.removeCallbacks(autoLaunchRunnable)
        val mode = when {
            Config.kioskMode && Config.passOnce -> "已锁定 · 放行中（重启恢复）"
            Config.kioskMode && watchServiceOn() -> "已锁定"
            Config.kioskMode -> "未生效 · 缺无障碍权限"
            Kiosk.isDeviceOwner(this) -> "强锁中"
            Kiosk.isHomeLocked(this) -> "弱锁中"
            else -> "未锁定"
        }

        val items = mutableListOf<String>()
        items.add("打开应用")
        items.add("观看时长")
        // 三个状态：没锁 → 开启；锁着 → 本次放行 / 彻底退出
        // （「结束放行」不用单独做按钮：重新打开儿童桌面就会自动结束）
        if (!Config.kioskMode) {
            items.add("开启儿童桌面")
        } else {
            // 不显示系统桌面的真实名字：家长不需要知道它叫聚好看还是小米电视桌面，
            // 固定文案在所有电视上一致，也更短（曾用运行时探测名，v1.24 改掉）
            items.add("临时回原桌面")
            items.add("退出儿童桌面")
        }
        if (!Settings.canDrawOverlays(this)) {
            items.add("悬浮窗权限")
        }
        items.add("开机自启检查")
        items.add("系统设置")
        items.add("修改密码")
        items.add("切换主题")
        items.add("选择应用")
        if (Kiosk.isDeviceOwner(this)) {
            items.add(
                if (Config.lockTask) "彻底锁死：开"
                else "彻底锁死：关"
            )
            items.add("撤销 Device Owner")
        } else {
            items.add("强锁设置方法")
        }

        val mode2 = if (Config.limitEnabled) {
            val d = if (Config.dailyMinutes <= 0) "每天不限" else "每天 ${Config.dailyMinutes} 分钟"
            val s = if (Config.singleMinutes <= 0) "单次不限" else "每 ${Config.singleMinutes} 分钟一休"
            "$d · $s"
        } else {
            "时长管控：关"
        }

        val overlayWarn = if (Config.limitEnabled && !Settings.canDrawOverlays(this) && !Kiosk.isDeviceOwner(this))
            "\n⚠️ 没给悬浮窗权限，到点盖不出锁屏页"
        else ""

        val targetDesc = when {
            Config.targets.isEmpty() -> "还没选（点下面「选择应用」）"
            !anyTargetInstalled() -> "勾选的应用都已卸载"
            Config.targets.size > 3 -> "已选 ${Config.targets.size} 个应用"
            else -> Config.targets.joinToString("、") { appLabelOrPkg(it) }
        }

        val summary = "$mode\n应用：$targetDesc\n$mode2\n主题：${KioskTheme.displayName()}\n今天已看 ${Timer.dayUsedSeconds() / 60} 分钟" +
            overlayWarn

        // 注意：setMessage 和 setItems 不能同时用 —— Material 布局里说明文字会占掉
        // 列表的位置，结果只剩一段文字和「关闭」按钮。所以把摘要塞进自定义标题。
        AlertDialog.Builder(this)
            .setCustomTitle(menuHeader("家长菜单", summary))
            .setItems(items.toTypedArray()) { _, which ->
                handleMenu(items[which])
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 弹窗顶部的标题 + 状态摘要（setMessage 会和 setItems 打架，只能这么做） */
    private fun menuHeader(title: String, summary: String): android.view.View {
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(14), dp(22), dp(4))

        box.addView(android.widget.TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(KioskTheme.color(context, R.attr.kioskTitle, R.color.cream_deep))
        })

        box.addView(android.widget.TextView(this).apply {
            text = summary
            textSize = 13f
            setTextColor(KioskTheme.color(context, R.attr.kioskText, R.color.cream_text))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        return box
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // 用 when{} 而不是 when(item)：菜单项里有运行时才拼出来的标签（系统桌面名各品牌不同），
    // 只能按前缀匹配。注意 Kotlin 的 when(有主语) 分支只支持 ==/in/is，不能塞 startsWith。
    private fun handleMenu(item: String) {
        when {
            item == "打开应用" -> launchTarget()
            item == "观看时长" -> openTimeSettings()
            item == "悬浮窗权限" -> requestOverlayPermission()
            item == "开机自启检查" -> showBootSelfCheck()
            item == "开启儿童桌面" -> enterKiosk()
            item.startsWith("临时回") -> passToStockLauncher()
            item == "结束放行（立即恢复锁定）" -> {
                Config.passOnce = false
                Toast.makeText(this, "已恢复锁定", Toast.LENGTH_SHORT).show()
                refreshTarget()
                refreshTimeInfo()
            }
            item == "退出儿童桌面" -> exitKiosk()
            item == "系统设置" -> {
                // 家长要进系统设置，先把看门狗停 10 分钟，否则一秒就被弹回来
                Config.watchdogPauseUntil = SystemClock.elapsedRealtime() + WATCH_PAUSE_MS
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "打不开系统设置", Toast.LENGTH_SHORT).show()
                }
            }
            item == "修改密码" -> changePin()
            item == "切换主题" -> showThemePicker()
            item == "选择应用" -> changeTarget()
            item == "彻底锁死：开" -> {
                Config.lockTask = false
                Kiosk.exitLockTask(this)
                Toast.makeText(this, "已关闭彻底锁死", Toast.LENGTH_SHORT).show()
            }
            item == "彻底锁死：关" -> {
                Config.lockTask = true
                Kiosk.enter(this)
                Toast.makeText(this, "已彻底锁死", Toast.LENGTH_SHORT).show()
            }
            item == "撤销 Device Owner" -> confirmClearOwner()
            item == "强锁设置方法" -> showStrongLockGuide()
        }
    }

    /**
     * 开启儿童桌面（看门狗模式）。
     *
     * 不碰系统桌面，只做两件事：把自己设为桌面 + 打开看门狗。
     * 系统桌面被海信固件钉死，永远抢不过来，所以 HOME 会先闪一下聚好看，看门狗再把人拉回来。
     */
    private fun enterKiosk() {
        Config.locked = true
        Config.kioskMode = true
        Config.passOnce = false
        Kiosk.setHomeLocked(this, true)

        if (watchServiceOn()) {
            Toast.makeText(this, "已开启", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("还差一步")
            .setMessage(
                "看门狗需要无障碍权限。\n" +
                    "接下来跳到系统设置，打开「儿童桌面」即可。\n" +
                    "（10 分钟内不会把你弹回来）"
            )
            .setPositiveButton("去打开") { _, _ ->
                Config.watchdogPauseUntil = SystemClock.elapsedRealtime() + WATCH_PAUSE_MS
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "打不开无障碍设置，请手动进系统设置", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 退出儿童桌面：关看门狗 + 撤掉自己的桌面身份，电视原样回来 */
    private fun exitKiosk() {
        Config.kioskMode = false
        Config.passOnce = false
        Config.locked = false
        Kiosk.exitLockTask(this)
        Kiosk.setHomeLocked(this, false)
        Toast.makeText(this, "已退出，按 HOME 回原桌面", Toast.LENGTH_LONG).show()

        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(home)
        } catch (e: Exception) {
        }
    }

    /**
     * 本次放行：回系统桌面自己用，但锁定状态一律不动。
     *
     * 和「退出儿童桌面」的区别：这个只放行这一轮，重启电视、或者重新打开一次儿童桌面，
     * 就自动锁回去，不用再手动点「开启儿童桌面」。
     */
    private fun passToStockLauncher() {
        Config.passOnce = true
        Toast.makeText(
            this,
            "已回原桌面，重启或重开儿童桌面后恢复锁定",
            Toast.LENGTH_LONG
        ).show()
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(home)
        } catch (e: Exception) {
        }
    }

    /** 无障碍服务有没有被打开 */
    private fun watchServiceOn(): Boolean {
        val enabled = try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        }.orEmpty()
        val full = "$packageName/${KioskWatchService::class.java.name}"
        val short = "$packageName/.KioskWatchService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    // ---------- 观看时长 ----------

    private fun openTimeSettings() {
        suppressAuto = true
        handler.removeCallbacks(autoLaunchRunnable)
        try {
            startActivity(Intent(this, TimeSettingsActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "打不开设置页", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已经授权了", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("悬浮窗权限")
            .setMessage(
                "电视系统把这个权限收回去了（海信会这么干）。\n" +
                    "到点弹出休息页、开机自启，都要这个权限。\n" +
                    "在接下来的页面里找到「儿童桌面」，选「允许」。"
            )
            .setPositiveButton("去开启") { _, _ -> openOverlaySettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(this, "打不开，请到 设置→应用→儿童桌面 手动授权", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun changePin() {
        PinDialog.inputNumber(this, "修改密码", "输入新的 4-8 位数字", 8) { value ->
            if (value.length in 4..8) {
                Config.pin = value
                Toast.makeText(this, "密码已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请输入 4-8 位数字", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 切换主题（皮肤）。选完落盘 + recreate，全局立即生效：
     * 桌面、休息页、时长设置、所有弹窗都跟着换。
     */
    private fun showThemePicker() {
        val values = arrayOf(KioskTheme.CREAM, KioskTheme.SPACE)
        val names = arrayOf(
            "奶油儿童（暖粉色，默认）",
            "深空影院（深色，晚上不刺眼）"
        )
        val checked = values.indexOf(Config.theme).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("切换主题")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                if (values[which] != Config.theme) {
                    Config.theme = values[which]
                    Toast.makeText(this, "已切换：${names[which].substringBefore("（")}", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 首页右下角按钮上的文字：告诉家长当前是哪套皮肤 */
    private fun refreshThemeBtn() {
        binding.themeBtn.text = "主题 · ${KioskTheme.displayName()}"
    }

    /** 点首页的主题按钮：两套之间循环，落盘后 recreate 全局生效 */
    private fun toggleTheme() {
        Config.theme =
            if (Config.theme == KioskTheme.SPACE) KioskTheme.CREAM else KioskTheme.SPACE
        Toast.makeText(this, "已切换：${KioskTheme.displayName()}", Toast.LENGTH_SHORT).show()
        recreate()
    }

    /**
     * 从已安装应用里挑，**可多选**。不让家长用遥控器敲包名。
     *
     * 已卸载的应用不出现在列表里（勾了也白勾），但会在标题摘要里提示数量。
     */
    private fun changeTarget() {
        val pm = packageManager
        val result = mutableListOf<android.content.pm.ResolveInfo>()

        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        result.addAll(pm.queryIntentActivities(leanback, 0))

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        result.addAll(pm.queryIntentActivities(launcher, 0))

        val apps = result
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "没读到已安装的应用列表", Toast.LENGTH_LONG).show()
            return
        }

        val labels = apps.map { "${it.loadLabel(pm)}" }.toTypedArray()
        val pkgs = apps.map { it.activityInfo.packageName }

        // 已勾选的先打钩；勾了但被卸载的也打钩，留着家长取消
        val checked = pkgs.map { it in Config.targets }.toBooleanArray()

        val selected = Config.targets.toMutableSet()
        val goneCount = selected.count { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                false
            } catch (e: Exception) {
                true
            }
        }
        val currentText = when {
            selected.isEmpty() -> "当前：还没选"
            goneCount > 0 -> "当前：${selected.size} 个（$goneCount 个已卸载）"
            else -> "当前：${selected.size} 个"
        }

        AlertDialog.Builder(this)
            .setCustomTitle(menuHeader("锁定哪些应用", currentText))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(pkgs[which]) else selected.remove(pkgs[which])
            }
            .setPositiveButton("确定") { _, _ ->
                Config.setTargets(selected)
                Kiosk.applyPolicy(this)
                refreshTarget()
                Toast.makeText(
                    this,
                    if (selected.isEmpty()) "已设为不锁定任何应用" else "已锁定 ${selected.size} 个应用",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("全不选") { _, _ ->
                Config.setTargets(emptySet())
                Kiosk.applyPolicy(this)
                refreshTarget()
                Toast.makeText(this, "已设为不锁定任何应用", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearOwner() {
        AlertDialog.Builder(this)
            .setTitle("撤销 Device Owner")
            .setMessage("撤销后强锁立即失效，可以正常卸载本应用。\n\n如果撤销失败，只能用 ADB 或恢复出厂。确定？")
            .setPositiveButton("确定撤销") { _, _ ->
                val ok = Kiosk.clearOwner(this)
                val msg = if (ok) "已撤销" else "撤销失败，请用 ADB 或恢复出厂"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「开机自启检查」—— 四条前提逐条打勾，缺哪条一眼看出来。
     * 开机自启失败从来不是"应用没做"，而是这四条里有一条没满足。
     */
    private fun showBootSelfCheck() {
        val overlay = Settings.canDrawOverlays(this)
        val homeLocked = Kiosk.isHomeLocked(this)
        val launcherName = Kiosk.stockLauncherName(this)

        val lines = buildString {
            append("① 锁定：")
            append(if (Config.locked) "开 ✅\n" else "关 ❌ 退出过，开机不自启\n")
            append("② 悬浮窗：")
            append(if (overlay) "已给 ✅\n" else "没给 ❌ 后台拉界面靠它\n")
            append("③ 开机广播：已听 ✅\n")
            append("④ 默认桌面：")
            append(if (homeLocked) "儿童桌面 ✅\n" else "「$launcherName」（看门狗兜底）\n")
        }

        val tail =
            "\n四项都正常，重启电视就会自动进儿童桌面。\n\n" +
                "还不行？去电视的「自启动管理 / 省电优化」里把儿童桌面改成允许，" +
                "海信、小米的固件默认会掐掉第三方应用。\n\n" +
                "注意：安装后至少要手动打开过一次，否则收不到开机广播。"

        AlertDialog.Builder(this)
            .setTitle("开机自启检查")
            .setMessage(lines + tail)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 「怎么让它真的锁住」。
     *
     * 全品牌通用：桌面包名运行时探测，Device Owner 支持情况也运行时探测，
     * 不预设任何一台电视的答案。
     */
    private fun showStrongLockGuide() {
        val launcher = Kiosk.stockLauncherPackage(this)
        val launcherName = Kiosk.stockLauncherName(this)
        val brand = Build.MANUFACTURER.trim().ifEmpty { "这台" }
        val ownerSupported = packageManager.hasSystemFeature("android.software.device_admin")

        val guide = if (ownerSupported) {
            // 带 Device Admin 特性的电视（索尼、多数盒子）：dpm 是更彻底的路
            "这台电视（$brand）支持 Device Owner，可以上强锁。\n\n" +
                "电脑上执行（电视不能登录任何账号）：\n" +
                "  adb shell dpm set-device-owner com.kidstv.kiosk/.KioskAdmin\n\n" +
                "成功后 HOME 键、返回键、最近任务全部失效，\n" +
                "只能长按菜单键 2 秒 + 密码进家长菜单。"
        } else if (launcher.isEmpty()) {
            "这台电视（$brand）不支持 Device Owner。\n\n" +
                "保持看门狗就行：设置 → 无障碍 → 打开「儿童桌面」，\n" +
                "按 HOME 会被拉回来，随时能从家长菜单退出。\n\n" +
                "想更狠（要 ADB，崩了按 HOME 没反应）：\n" +
                "  查桌面包名：adb shell cmd package resolve-activity " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME\n" +
                "  停用：adb shell pm disable-user --user 0 <桌面包名>\n" +
                "  恢复：adb shell pm enable <桌面包名>"
        } else {
            "这台电视（$brand）不支持 Device Owner。\n\n" +
                "保持看门狗就行：设置 → 无障碍 → 打开「儿童桌面」，\n" +
                "按 HOME 会被拉回来，随时能从家长菜单退出。\n\n" +
                "想更狠（要 ADB，崩了按 HOME 没反应）：\n" +
                "  停用：adb shell pm disable-user --user 0 $launcher\n" +
                "  恢复：adb shell pm enable $launcher\n" +
                "  （$launcherName）"
        }

        AlertDialog.Builder(this)
            .setTitle("怎么锁得更死")
            .setMessage(guide)
            .setPositiveButton("知道了", null)
            .show()
    }
}
