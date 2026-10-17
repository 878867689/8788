package com.mcpbridge.android

import android.app.Application

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
