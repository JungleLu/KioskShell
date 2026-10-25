package com.kidstv.kiosk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set

        val prefs: SharedPreferences
            get() = instance.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    }
}
