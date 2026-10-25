package com.kidstv.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class KioskAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Kiosk.applyPolicy(context)
    }
}
