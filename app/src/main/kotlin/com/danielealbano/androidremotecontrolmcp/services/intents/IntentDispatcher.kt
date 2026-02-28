package com.danielealbano.androidremotecontrolmcp.services.intents

data class SendIntentRequest(
    val type: String,
    val action: String? = null,
    val data: String? = null,
    val component: String? = null,
    val extras: Map<String, Any?>? = null,
    val extrasTypes: Map<String, String>? = null,
    val flags: List<String>? = null,
)

interface IntentDispatcher {
    suspend fun sendIntent(request: SendIntentRequest): Result<Unit>

    suspend fun openUri(
        uri: String,
        packageName: String? = null,
        mimeType: String? = null,
    ): Result<Unit>
}
