package com.danielealbano.androidremotecontrolmcp.services.tunnel

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire frames exchanged with the relay over the device WebSocket (text frames, JSON).
 *
 * Relay → device: [RelayRequestFrame] (`type=request`) and `{"type":"ping"}`.
 * Device → relay: [RelayResponseFrame] (`type=response`) and `{"type":"pong"}`.
 * Header values are always arrays; bodies are base64.
 */
@Serializable
internal data class RelayRequestFrame(
    val type: String = RelayFrameCodec.TYPE_REQUEST,
    val id: String,
    val method: String,
    val path: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

@Serializable
internal data class RelayResponseFrame(
    val type: String = RelayFrameCodec.TYPE_RESPONSE,
    val id: String,
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

@Serializable
internal data class RelayControlFrame(
    val type: String,
)

/** Decoded inbound frame. */
internal sealed class RelayInbound {
    data class Request(
        val frame: RelayRequestFrame,
    ) : RelayInbound()

    data object Ping : RelayInbound()

    data object Pong : RelayInbound()

    data class Unknown(
        val type: String,
    ) : RelayInbound()

    data class Malformed(
        val reason: String,
    ) : RelayInbound()
}

internal object RelayFrameCodec {
    const val TYPE_REQUEST = "request"
    const val TYPE_RESPONSE = "response"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Never throws: malformed input yields [RelayInbound.Malformed]. */
    fun decode(text: String): RelayInbound =
        try {
            decodeElement(json.parseToJsonElement(text))
        } catch (e: SerializationException) {
            RelayInbound.Malformed(e.message ?: "invalid JSON")
        } catch (e: IllegalArgumentException) {
            RelayInbound.Malformed(e.message ?: "invalid frame")
        }

    fun encode(frame: RelayResponseFrame): String = json.encodeToString(RelayResponseFrame.serializer(), frame)

    fun encodePong(): String = json.encodeToString(RelayControlFrame.serializer(), RelayControlFrame(TYPE_PONG))

    private fun decodeElement(element: JsonElement): RelayInbound {
        val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: ""
        return when (type) {
            TYPE_REQUEST -> RelayInbound.Request(json.decodeFromJsonElement(RelayRequestFrame.serializer(), element))
            TYPE_PING -> RelayInbound.Ping
            TYPE_PONG -> RelayInbound.Pong
            else -> RelayInbound.Unknown(type)
        }
    }
}
