package com.danielealbano.androidremotecontrolmcp.services.camera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServiceLifecycleOwnerTest {
    @Test
    fun `initial state is INITIALIZED`() {
        val owner = ServiceLifecycleOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
    }

    @Test
    fun `start transitions to RESUMED`() {
        val owner = ServiceLifecycleOwner()
        owner.start()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    @Test
    fun `stop after start transitions to DESTROYED`() {
        val owner = ServiceLifecycleOwner()
        owner.start()
        owner.stop()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun `start transitions through CREATED then STARTED then RESUMED in order`() {
        val owner = ServiceLifecycleOwner()
        val observedStates = mutableListOf<Lifecycle.State>()

        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, _ ->
                observedStates.add(owner.lifecycle.currentState)
            },
        )

        owner.start()

        assertEquals(
            listOf(
                Lifecycle.State.CREATED,
                Lifecycle.State.STARTED,
                Lifecycle.State.RESUMED,
            ),
            observedStates,
        )
    }

    @Test
    fun `stop transitions through STARTED then CREATED then DESTROYED in order`() {
        val owner = ServiceLifecycleOwner()
        owner.start()

        val observedStates = mutableListOf<Lifecycle.State>()

        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, _ ->
                observedStates.add(owner.lifecycle.currentState)
            },
        )

        // Clear catch-up events from observer registration at RESUMED state
        observedStates.clear()

        owner.stop()

        assertEquals(
            listOf(
                Lifecycle.State.STARTED,
                Lifecycle.State.CREATED,
                Lifecycle.State.DESTROYED,
            ),
            observedStates,
        )
    }

    @Test
    fun `calling stop without start does not crash`() {
        val owner = ServiceLifecycleOwner()
        owner.stop()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
