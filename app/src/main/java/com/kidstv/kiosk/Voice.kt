package com.kidstv.kiosk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build

/**
 * 到点提示语音。
 *
 * 三条设计：
 *  1. 音频打包在 res/raw 里，不依赖电视有没有中文 TTS 引擎（多数电视没有）。
 *  2. 只请求 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK：让底下的播放器把声音压下去，
 *     语音不用跟动画片抢音量。不用 GAIN_TRANSIENT —— 那会让第三方播放器暂停，
 *     而它们不一定自动续播，孩子回来还得手动按播放。
 *  3. 用「锚点」去重：GuardService 每 8 秒都会把锁屏页重新拉起来一次，
 *     语音若跟着 onCreate 走，休息 5 分钟能响几十遍。
 */
object Voice {

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private var lastRestAnchor = 0L
    private var lastDayAnchor = ""

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /** 休息开始说一次（同一次休息只说一次） */
    fun playRestOnce(ctx: Context) {
        if (!Config.voiceEnabled) return
        val anchor = Config.restUntil
        if (anchor <= 0L || anchor == lastRestAnchor) return
        lastRestAnchor = anchor
        play(ctx, R.raw.voice_rest)
    }

    /** 每日额度用完说一次（同一天只说一次） */
    fun playDayOnce(ctx: Context) {
        if (!Config.voiceEnabled) return
        val anchor = Timer.todayKey()
        if (anchor == lastDayAnchor) return
        lastDayAnchor = anchor
        play(ctx, R.raw.voice_day_over)
    }

    /** 设置页「试听」，不受开关限制 */
    fun preview(ctx: Context) {
        play(ctx, R.raw.voice_rest)
    }

    fun release() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        abandonFocus()
    }

    private fun play(ctx: Context, resId: Int) {
        release()
        requestFocus(ctx)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            ctx.resources.openRawResourceFd(resId).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setOnCompletionListener { release() }
            mp.setOnErrorListener { _, _, _ ->
                release()
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            release()
        }
    }

    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun abandonFocus() {
        val am = App.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
        }
        focusRequest = null
    }
}
