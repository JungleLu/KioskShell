package com.kidstv.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 后台计时。每秒跳一次，到点把锁屏页盖到最上面。
 *
 * 之所以用前台服务：Android 8 以后普通后台服务会被系统掐掉，
 * 而计时一旦断掉，时长限制就等于没有。
 */
class GuardService : Service() {

    companion object {
        private const val CHANNEL_ID = "guard"
        private const val NOTIFY_ID = 1001
        private const val RETRY_LOCK_MS = 8000L
        private const val NOTIFY_EVERY_TICKS = 5
        private const val OVERLAY_CHECK_MS = 30_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, GuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                // 系统不允许就算了，桌面上的拦截仍然有效
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, GuardService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastLockAt = 0L
    private var ticks = 0
    private var receiver: BroadcastReceiver? = null
    private var lastOverlayCheck = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timer.screenOn = isScreenOn()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        receiver = null
        Timer.flush()
        super.onDestroy()
    }

    // ---------------- 计时 ----------------

    private fun onTick() {
        val result = Timer.tick()
        when (result) {
            Timer.TickResult.REST -> showLock(LockActivity.MODE_REST)
            Timer.TickResult.DAY_OVER -> showLock(LockActivity.MODE_DAY)
            Timer.TickResult.NONE -> reassertLock()
        }

        checkOverlayPermission()

        ticks++
        if (ticks % NOTIFY_EVERY_TICKS == 0) updateNotification()
    }

    /**
     * 悬浮窗权限巡检：海信固件会无声收回这个权限（实测过），
     * 丢了之后到点盖不出休息页、开机拉不起界面，而且不报错。
     * 这里每 30 秒查一次，刚丢的那一刻记标记 + 弹 Toast，
     * 家长一回桌面 MainActivity 看到标记就会弹授权引导。
     */
    private fun checkOverlayPermission() {
        if (!Config.kioskMode || !Config.limitEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayCheck < OVERLAY_CHECK_MS) return
        lastOverlayCheck = now
        val has = try {
            Settings.canDrawOverlays(this)
        } catch (e: Exception) {
            true // 查不动时当作有，别误报
        }
        if (!has && !Config.overlayLost) {
            Config.overlayLost = true
            try {
                Toast.makeText(
                    applicationContext,
                    "悬浮窗权限被系统收回了，回儿童桌面重新授予",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
            }
        } else if (has && Config.overlayLost) {
            Config.overlayLost = false
        }
    }

    /** 孩子按 HOME 逃掉锁屏、又从最近任务钻回视频时，把锁屏重新盖上去 */
    private fun reassertLock() {
        if (!Timer.watching) return
        val now = System.currentTimeMillis()
        if (now - lastLockAt < RETRY_LOCK_MS) return
        if (Timer.restRemainingSeconds() > 0) {
            showLock(LockActivity.MODE_REST)
        } else if (Config.dayBlocked) {
            showLock(LockActivity.MODE_DAY)
        }
    }

    private fun showLock(mode: String) {
        lastLockAt = System.currentTimeMillis()
        val intent = Intent(this, LockActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(LockActivity.EXTRA_MODE, mode)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 没给「悬浮窗权限」又没上强锁时，Android 10+ 可能拦住。
            // 这时孩子一按 HOME 回到桌面，桌面上也会挡住，不至于完全失效。
        }
    }

    // ---------------- 通知 ----------------

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFY_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFY_ID, notification)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFY_ID, buildNotification())
        } catch (e: Exception) {
        }
    }

    private fun buildNotification(): android.app.Notification {
        ensureChannel()

        val text = when {
            !Config.limitEnabled -> "时长管控已关闭"
            Config.dayBlocked -> "今天的时间已用完"
            Timer.restRemainingSeconds() > 0 -> "休息中，还剩 ${formatMinutes(Timer.restRemainingSeconds())}"
            else -> {
                val single = Timer.sessionRemainingSeconds()
                val day = Timer.dayRemainingSeconds()
                val a = if (single < 0) "单次不限" else "本次还剩 ${formatMinutes(single)}"
                val b = if (day < 0) "每日不限" else "今天还剩 ${formatMinutes(day)}"
                "$a · $b"
            }
        }

        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kiosk)
            .setContentTitle("儿童桌面")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "观看时长",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    // ---------------- 屏幕开关 ----------------

    private fun isScreenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                power.isInteractive
            } else {
                @Suppress("DEPRECATION")
                power.isScreenOn
            }
        } catch (e: Exception) {
            true
        }
    }

    /** 电视待机/息屏时不该算作观看时间 */
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> Timer.screenOn = false
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> Timer.screenOn = true
                }
            }
        }
        receiver = r
        try {
            registerReceiver(r, filter)
        } catch (e: Exception) {
        }
    }

    private fun formatMinutes(seconds: Int): String {
        if (seconds <= 0) return "0 分钟"
        if (seconds < 60) return "$seconds 秒"
        val m = seconds / 60
        val s = seconds % 60
        return if (s == 0) "$m 分钟" else "$m 分 $s 秒"
    }
}
