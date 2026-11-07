package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import com.jakewharton.processphoenix.ProcessPhoenix
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface AppRestarter {
    fun restart()
}

class AppRestarterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AppRestarter {
        override fun restart() {
            ProcessPhoenix.triggerRebirth(context)
        }
    }
