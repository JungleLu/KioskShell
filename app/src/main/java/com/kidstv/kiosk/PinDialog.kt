package com.kidstv.kiosk

import android.app.Activity

/**
 * 门禁弹窗。签名保持不变，内部转调自绘键盘的 [ParentGateDialog]，
 * 这样 LockActivity / MainActivity 的调用点一行都不用改。
 */
object PinDialog {

    fun show(
        activity: Activity,
        title: String = "家长验证",
        onDismiss: () -> Unit = {},
        onOk: () -> Unit
    ) {
        ParentGateDialog.show(activity, title, onDismiss, onOk)
    }

    /** 只输入一串数字（改家长密码用），同样走自绘键盘 */
    fun inputNumber(
        activity: Activity,
        title: String,
        prompt: String,
        maxLen: Int = 8,
        onOk: (String) -> Unit
    ) {
        ParentGateDialog.inputNumber(activity, title, prompt, maxLen, onOk)
    }
}
