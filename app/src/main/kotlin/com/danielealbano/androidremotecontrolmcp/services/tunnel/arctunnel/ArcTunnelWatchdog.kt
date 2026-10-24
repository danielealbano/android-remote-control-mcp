package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.Call
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ArcTunnelWatchdog {
    private const val TAG = "MCP:ArcTunnelWatchdog"
    const val POLL_MS = 5_000L
    val SILENCE_NANOS: Long = TimeUnit.SECONDS.toNanos(90)

    /** Polls until [call] is cancelled; on 90 s of silence sets [silent] and cancels the call. */
    suspend fun watch(
        call: Call,
        lastFrameAt: AtomicLong,
        silent: AtomicBoolean,
        now: () -> Long = System::nanoTime,
    ) {
        while (!call.isCanceled()) {
            delay(POLL_MS)
            if (now() - lastFrameAt.get() > SILENCE_NANOS) {
                Log.w(TAG, "no control frame for 90s; dropping the connection")
                silent.set(true)
                call.cancel()
            }
        }
    }
}
