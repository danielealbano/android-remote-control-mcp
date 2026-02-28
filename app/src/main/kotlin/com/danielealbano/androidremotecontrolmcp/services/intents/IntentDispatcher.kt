package com.danielealbano.androidremotecontrolmcp.services.intents

/**
 * Parameters for [IntentDispatcher.sendIntent].
 *
 * @property type Delivery mode: `"activity"`, `"broadcast"`, or `"service"`.
 * @property action Intent action string (e.g., `"android.intent.action.VIEW"`).
 * @property data Data URI for the intent.
 * @property component Target component as `"package/class"`.
 * @property extras Key-value extras with auto type inference.
 * @property extrasTypes Explicit type overrides for extras keys.
 * @property flags Intent flag names resolved via reflection.
 */
data class SendIntentRequest(
    val type: String,
    val action: String? = null,
    val data: String? = null,
    val component: String? = null,
    val extras: Map<String, Any?>? = null,
    val extrasTypes: Map<String, String>? = null,
    val flags: List<String>? = null,
)

/**
 * Service for dispatching Android intents and opening URIs.
 *
 * Abstracts [android.content.Context] intent operations behind a testable interface.
 * Returns [Result] to signal success or failure without throwing.
 */
interface IntentDispatcher {
    /** Builds and dispatches an intent based on [request] parameters. */
    suspend fun sendIntent(request: SendIntentRequest): Result<Unit>

    /** Opens [uri] via `ACTION_VIEW`, optionally targeting [packageName] with [mimeType]. */
    suspend fun openUri(
        uri: String,
        packageName: String? = null,
        mimeType: String? = null,
    ): Result<Unit>
}
