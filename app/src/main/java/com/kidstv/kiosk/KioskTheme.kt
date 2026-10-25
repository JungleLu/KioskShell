package com.kidstv.kiosk

import android.app.Activity
import android.content.Context
import androidx.core.content.ContextCompat

/**
 * 主题切换（皮肤）。
 *
 * 两套：
 *  - [CREAM] 奶油儿童（默认）：暖粉底、大圆角，孩子看着亲切
 *  - [SPACE] 深空影院：深蓝黑底，晚上看不刺眼，遥控焦点框更清晰
 *
 * 实现方式：themes.xml 里每套主题给同一组自定义 attr（kioskText / kioskCard…）
 * 赋不同的值；布局和代码只引用 attr，不直接引用颜色。
 * 活动在 super.onCreate 之前调 [apply]，弹窗由 AppCompat 自动读
 * alertDialogTheme 跟随，不用手动包 ContextThemeWrapper。
 */
object KioskTheme {

    const val CREAM = "cream"
    const val SPACE = "space"

    fun styleId(theme: String = Config.theme): Int =
        if (theme == SPACE) R.style.Theme_Kiosk_Space else R.style.Theme_Kiosk_Cream

    /** 活动主题。必须在 super.onCreate() 之前调用 */
    fun apply(activity: Activity) {
        activity.setTheme(styleId())
    }

    /** 菜单、弹窗里显示的名字 */
    fun displayName(theme: String = Config.theme): String =
        if (theme == SPACE) "深空影院" else "奶油儿童"

    /**
     * 按当前主题取一个 attr 颜色。代码里 setTextColor 用这个，
     * 别用 ContextCompat.getColor(R.color.xxx)——那会写死一套皮肤。
     */
    fun color(context: Context, attr: Int, fallbackRes: Int): Int {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            ta.getColor(0, ContextCompat.getColor(context, fallbackRes))
        } finally {
            ta.recycle()
        }
    }
}
