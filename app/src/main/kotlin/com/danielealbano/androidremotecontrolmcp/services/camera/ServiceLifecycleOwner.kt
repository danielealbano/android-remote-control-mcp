package com.danielealbano.androidremotecontrolmcp.services.camera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A [LifecycleOwner] usable from a Service context for CameraX binding.
 *
 * CameraX requires a [LifecycleOwner] to manage camera lifecycle.
 * Since [android.app.Service] does not implement [LifecycleOwner],
 * this class provides a manually controlled lifecycle.
 *
 * Usage:
 * 1. Call [start] before binding CameraX use cases
 * 2. Use the camera
 * 3. Call [stop] to unbind and release camera resources
 */
class ServiceLifecycleOwner : LifecycleOwner {
    @Suppress("VisibleForTests")
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * Transitions lifecycle through INITIALIZED -> CREATED -> STARTED -> RESUMED,
     * enabling CameraX use cases.
     */
    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Transitions lifecycle through RESUMED -> STARTED -> CREATED -> DESTROYED,
     * properly releasing CameraX resources. Must not skip states to ensure
     * CameraX lifecycle observers receive all expected callbacks.
     */
    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
