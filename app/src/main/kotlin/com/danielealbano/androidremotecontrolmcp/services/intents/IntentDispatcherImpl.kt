package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class IntentDispatcherImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : IntentDispatcher {

    override suspend fun sendIntent(
        type: String,
        action: String?,
        data: String?,
        component: String?,
        extras: Map<String, Any?>?,
        extrasTypes: Map<String, String>?,
        flags: List<String>?,
    ): Result<Unit> {
        if (type !in VALID_TYPES) {
            return Result.failure(
                IllegalArgumentException("Invalid intent type: '$type'. Must be one of: activity, broadcast, service"),
            )
        }

        return try {
            val intent = Intent()

            if (action != null) {
                intent.action = action
            }

            if (data != null) {
                intent.data = Uri.parse(data)
            }

            if (component != null) {
                val componentResult = parseComponent(component)
                if (componentResult.isFailure) {
                    return componentResult.map { }
                }
                intent.component = componentResult.getOrThrow()
            }

            // Resolve and apply flags; auto-add FLAG_ACTIVITY_NEW_TASK for "activity"
            val allFlags = flags.orEmpty().toMutableList()
            if (type == TYPE_ACTIVITY && FLAG_ACTIVITY_NEW_TASK !in allFlags) {
                allFlags.add(FLAG_ACTIVITY_NEW_TASK)
            }
            if (allFlags.isNotEmpty()) {
                val flagsResult = resolveFlags(allFlags)
                if (flagsResult.isFailure) {
                    return flagsResult.map { }
                }
                intent.flags = flagsResult.getOrThrow()
            }

            // Apply extras
            extras?.forEach { (key, value) ->
                putExtraWithInference(intent, key, value, extrasTypes?.get(key))
            }

            // Dispatch
            when (type) {
                TYPE_ACTIVITY -> context.startActivity(intent)
                TYPE_BROADCAST -> context.sendBroadcast(intent)
                TYPE_SERVICE -> context.startService(intent)
            }

            Logger.i(TAG, "Intent dispatched: type=$type, action=$action")
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No activity found to handle intent", e)
            Result.failure(IllegalArgumentException("No activity found to handle intent"))
        } catch (e: SecurityException) {
            Logger.w(TAG, "Permission denied for intent", e)
            Result.failure(IllegalArgumentException("Permission denied: not allowed to start component"))
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Illegal state for intent dispatch", e)
            Result.failure(IllegalStateException("Cannot start component: background start restriction"))
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "Invalid argument for intent", e)
            Result.failure(e)
        }
    }

    override suspend fun openUri(
        uri: String,
        packageName: String?,
        mimeType: String?,
    ): Result<Unit> =
        try {
            val intent = if (mimeType != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uri), mimeType)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (packageName != null) {
                intent.setPackage(packageName)
            }

            context.startActivity(intent)
            Logger.i(TAG, "URI opened: $uri")
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No app found to handle URI: $uri", e)
            Result.failure(IllegalArgumentException("No app found to handle URI"))
        } catch (e: SecurityException) {
            Logger.w(TAG, "Permission denied opening URI: $uri", e)
            Result.failure(IllegalArgumentException("Permission denied: not allowed to open URI"))
        }

    @Suppress("CyclomaticComplexity", "ThrowsCount")
    private fun putExtraWithInference(intent: Intent, key: String, value: Any?, typeOverride: String?) {
        try {
            if (typeOverride != null) {
                putExtraWithTypeOverride(intent, key, value, typeOverride)
            } else {
                putExtraWithAutoInference(intent, key, value)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to convert extra '$key': ${e.message}", e)
        }
    }

    @Suppress("ThrowsCount")
    private fun putExtraWithTypeOverride(intent: Intent, key: String, value: Any?, typeOverride: String) {
        when (typeOverride) {
            "string" -> intent.putExtra(key, value.toString())
            "int" -> intent.putExtra(key, (value as? Number)?.toInt() ?: value.toString().toInt())
            "long" -> intent.putExtra(key, (value as? Number)?.toLong() ?: value.toString().toLong())
            "float" -> intent.putExtra(key, (value as? Number)?.toFloat() ?: value.toString().toFloat())
            "double" -> intent.putExtra(key, (value as? Number)?.toDouble() ?: value.toString().toDouble())
            "boolean" -> intent.putExtra(
                key,
                when (value) {
                    is Boolean -> value
                    else -> value.toString().toBooleanStrict()
                },
            )
            else -> throw IllegalArgumentException(
                "Unsupported extras_types value: '$typeOverride'. Supported: string, int, long, float, double, boolean",
            )
        }
    }

    @Suppress("CyclomaticComplexity")
    private fun putExtraWithAutoInference(intent: Intent, key: String, value: Any?) {
        when {
            value == null -> return
            value is String -> intent.putExtra(key, value)
            value is Boolean -> intent.putExtra(key, value)
            value is Number -> putNumericExtra(intent, key, value)
            value is List<*> && value.all { it is String } -> {
                intent.putExtra(key, ArrayList(value.filterIsInstance<String>()))
            }
            else -> throw IllegalArgumentException(
                "Cannot infer extra type for key '$key': unsupported value type",
            )
        }
    }

    private fun putNumericExtra(intent: Intent, key: String, value: Number) {
        val doubleVal = value.toDouble()
        if (doubleVal % 1.0 == 0.0) {
            // No decimal part — determine Int vs Long
            val longVal = value.toLong()
            if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) {
                intent.putExtra(key, longVal.toInt())
            } else {
                intent.putExtra(key, longVal)
            }
        } else {
            intent.putExtra(key, doubleVal)
        }
    }

    private fun resolveFlags(flagNames: List<String>): Result<Int> {
        var combined = 0
        for (name in flagNames) {
            val flagValue = flagMap[name]
                ?: return Result.failure(IllegalArgumentException("Unknown flag: '$name'"))
            combined = combined or flagValue
        }
        return Result.success(combined)
    }

    private fun parseComponent(component: String): Result<ComponentName> {
        val slashIndex = component.indexOf('/')
        if (slashIndex < 0) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid component format: '$component'. Expected 'package/class'",
                ),
            )
        }
        val pkg = component.substring(0, slashIndex)
        val cls = component.substring(slashIndex + 1)
        return Result.success(ComponentName(pkg, cls))
    }

    companion object {
        private const val TAG = "MCP:IntentDispatcher"
        private const val TYPE_ACTIVITY = "activity"
        private const val TYPE_BROADCAST = "broadcast"
        private const val TYPE_SERVICE = "service"
        private const val FLAG_ACTIVITY_NEW_TASK = "FLAG_ACTIVITY_NEW_TASK"
        private val VALID_TYPES = setOf(TYPE_ACTIVITY, TYPE_BROADCAST, TYPE_SERVICE)

        val flagMap: Map<String, Int> by lazy {
            Intent::class.java.fields
                .filter { it.name.startsWith("FLAG_") && it.type == Int::class.javaPrimitiveType }
                .associate { it.name to it.getInt(null) }
        }
    }
}
