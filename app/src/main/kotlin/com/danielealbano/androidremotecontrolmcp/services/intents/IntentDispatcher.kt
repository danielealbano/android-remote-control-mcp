package com.danielealbano.androidremotecontrolmcp.services.intents

interface IntentDispatcher {
    suspend fun sendIntent(
        type: String,
        action: String? = null,
        data: String? = null,
        component: String? = null,
        extras: Map<String, Any?>? = null,
        extrasTypes: Map<String, String>? = null,
        flags: List<String>? = null,
    ): Result<Unit>

    suspend fun openUri(
        uri: String,
        packageName: String? = null,
        mimeType: String? = null,
    ): Result<Unit>
}
