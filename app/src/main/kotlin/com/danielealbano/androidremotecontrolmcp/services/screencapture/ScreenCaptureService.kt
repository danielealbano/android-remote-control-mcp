package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
