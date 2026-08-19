package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection

class McpInputMethodService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentConnection(): InputConnection? = currentInputConnection

    companion object {
        @Volatile
        var instance: McpInputMethodService? = null
            private set
    }
}
