package com.danielealbano.androidremotecontrolmcp.debug

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState

internal fun enrollmentLabel(state: ArcTunnelEnrollmentState): String =
    when (state) {
        is ArcTunnelEnrollmentState.Idle -> "Idle"
        is ArcTunnelEnrollmentState.Enrolling -> "Enrolling"
        is ArcTunnelEnrollmentState.Failed -> "Failed: ${state.message}"
    }

internal fun tunnelLabel(status: TunnelStatus): String =
    when (status) {
        is TunnelStatus.Disconnected -> "Disconnected"
        is TunnelStatus.Connecting -> "Connecting"
        is TunnelStatus.Connected -> "Connected"
        is TunnelStatus.Error -> "Error: ${status.message}"
    }

internal fun serverLabel(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Stopped -> "Stopped"
        is ServerStatus.Starting -> "Starting"
        is ServerStatus.Running -> "Running"
        is ServerStatus.Stopping -> "Stopping"
        is ServerStatus.Error -> "Error: ${status.message}"
    }
