package com.ts.messenger

import android.app.Application

class TsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TsApp
            private set
    }
}
