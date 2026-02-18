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
 * Typical usage:
 * 1. Create a [ServiceLifecycleOwner] instance and pass it when binding CameraX use cases
 *    via [androidx.camera.lifecycle.ProcessCameraProvider.bindToLifecycle]
 * 2. Call [start] to transition the lifecycle to RESUMED, activating bound use cases
 * 3. Use the camera (capture photo, record video, etc.)
 * 4. Call [stop] to transition to DESTROYED, releasing camera resources
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
