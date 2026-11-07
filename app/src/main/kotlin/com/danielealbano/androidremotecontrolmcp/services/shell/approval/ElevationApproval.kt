package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_DISPLAY_COMMAND_CHARS = 500
private const val ELLIPSIS = "…"

object ElevationApprovalIntents {
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_APPROVED = "approved"

    fun resolve(
        intent: Intent,
        coordinator: ElevationApprovalCoordinator,
    ) {
        val id = intent.getIntExtra(EXTRA_REQUEST_ID, 0)
        if (id > 0) coordinator.resolve(id, intent.getBooleanExtra(EXTRA_APPROVED, false))
    }
}

enum class ElevationVerdict {
    APPROVED,
    DENIED,
    TIMED_OUT,
    CANCELLED,
}

data class PendingElevation(
    val id: Int,
    val command: String,
    val justification: String,
) {
    val displayCommand: String
        get() =
            if (command.length > MAX_DISPLAY_COMMAND_CHARS) {
                command.take(MAX_DISPLAY_COMMAND_CHARS) + ELLIPSIS
            } else {
                command
            }
}

interface ElevationApprovalNotifier {
    fun canNotify(): Boolean

    fun post(request: PendingElevation)

    fun cancel(id: Int)
}

interface ElevationApprovalCoordinator {
    val pending: StateFlow<List<PendingElevation>>

    suspend fun request(
        command: String,
        justification: String,
        timeoutMs: Long,
    ): ElevationVerdict

    fun resolve(
        id: Int,
        approved: Boolean,
    )

    fun open()

    fun close()
}

@Singleton
class ElevationApprovalCoordinatorImpl
    @Inject
    constructor(
        private val notifier: ElevationApprovalNotifier,
    ) : ElevationApprovalCoordinator {
        private val nextId = AtomicInteger(1)
        private val answers = ConcurrentHashMap<Int, CompletableDeferred<ElevationVerdict>>()
        private val mutablePending = MutableStateFlow<List<PendingElevation>>(emptyList())

        @Volatile
        private var accepting = false

        override val pending: StateFlow<List<PendingElevation>> = mutablePending.asStateFlow()

        override suspend fun request(
            command: String,
            justification: String,
            timeoutMs: Long,
        ): ElevationVerdict {
            if (!accepting) return ElevationVerdict.CANCELLED
            val request = PendingElevation(nextId.getAndIncrement(), command, justification)
            val answer = CompletableDeferred<ElevationVerdict>()
            answers[request.id] = answer
            if (!accepting) answer.complete(ElevationVerdict.CANCELLED)
            mutablePending.update { it + request }
            return try {
                notifier.post(request)
                withTimeoutOrNull(timeoutMs) { answer.await() } ?: ElevationVerdict.TIMED_OUT
            } finally {
                answers.remove(request.id)
                mutablePending.update { list -> list.filterNot { it.id == request.id } }
                notifier.cancel(request.id)
            }
        }

        override fun resolve(
            id: Int,
            approved: Boolean,
        ) {
            val answer = answers[id]
            if (answer == null) {
                notifier.cancel(id)
            } else {
                answer.complete(if (approved) ElevationVerdict.APPROVED else ElevationVerdict.DENIED)
            }
        }

        override fun open() {
            accepting = true
        }

        override fun close() {
            accepting = false
            answers.values.forEach { it.complete(ElevationVerdict.CANCELLED) }
        }
    }
