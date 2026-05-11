package com.example.monitorize

import android.app.Service
import android.content.Intent
import android.os.IBinder

class StreamForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
