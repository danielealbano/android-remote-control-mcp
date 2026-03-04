package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolPermissionsConfig(
    val disabledTools: Set<String> = emptySet(),
    val disabledParams: Map<String, Set<String>> = emptyMap(),
) {
    fun isToolEnabled(toolName: String): Boolean = toolName !in disabledTools

    fun isParamEnabled(
        toolName: String,
        paramName: String,
    ): Boolean = paramName !in (disabledParams[toolName] ?: emptySet())

    fun toJson(): String =
        buildJsonObject {
            put("disabledTools", buildJsonArray { disabledTools.forEach { add(it) } })
            put(
                "disabledParams",
                buildJsonObject {
                    disabledParams.forEach { (tool, params) ->
                        put(tool, buildJsonArray { params.forEach { add(it) } })
                    }
                },
            )
        }.toString()

    companion object {
        fun fromJson(json: String): ToolPermissionsConfig? =
            try {
                val obj = Json.parseToJsonElement(json).jsonObject
                val disabledTools =
                    obj["disabledTools"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.toSet()
                        ?: emptySet()
                val disabledParams =
                    obj["disabledParams"]
                        ?.jsonObject
                        ?.mapValues { (_, v) ->
                            v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        }
                        ?: emptyMap()
                ToolPermissionsConfig(disabledTools = disabledTools, disabledParams = disabledParams)
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        fun fromJsonOrDefault(json: String?): ToolPermissionsConfig =
            if (json == null) ToolPermissionsConfig() else fromJson(json) ?: ToolPermissionsConfig()
    }
}
