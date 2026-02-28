package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class IntentDispatcherImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : IntentDispatcher {
        override suspend fun sendIntent(request: SendIntentRequest): Result<Unit> {
            if (request.type !in VALID_TYPES) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid intent type: '${request.type}'. Must be one of: activity, broadcast, service",
                    ),
                )
            }

            return try {
                val intent = buildIntent(request)
                when (request.type) {
                    TYPE_ACTIVITY -> context.startActivity(intent)
                    TYPE_BROADCAST -> context.sendBroadcast(intent)
                    TYPE_SERVICE -> context.startService(intent)
                }
                Logger.i(TAG, "Intent dispatched: type=${request.type}, action=${request.action}")
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
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Logger.e(TAG, "Unexpected error dispatching intent", e)
                Result.failure(IllegalStateException("Intent dispatch failed unexpectedly"))
            }
        }

        private fun buildIntent(request: SendIntentRequest): Intent {
            val intent = Intent()

            if (request.action != null) {
                intent.action = request.action
            }

            if (request.data != null) {
                intent.data = Uri.parse(request.data)
            }

            if (request.component != null) {
                intent.component = parseComponent(request.component).getOrThrow()
            }

            applyFlags(intent, request)
            request.extras?.forEach { (key, value) ->
                putExtraWithInference(intent, key, value, request.extrasTypes?.get(key))
            }
            return intent
        }

        private fun applyFlags(
            intent: Intent,
            request: SendIntentRequest,
        ) {
            val allFlags = request.flags.orEmpty().toMutableList()
            if (request.type == TYPE_ACTIVITY && FLAG_ACTIVITY_NEW_TASK !in allFlags) {
                allFlags.add(FLAG_ACTIVITY_NEW_TASK)
            }
            if (allFlags.isNotEmpty()) {
                intent.flags = resolveFlags(allFlags).getOrThrow()
            }
        }

        override suspend fun openUri(
            uri: String,
            packageName: String?,
            mimeType: String?,
        ): Result<Unit> =
            try {
                val intent =
                    if (mimeType != null) {
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
                Logger.i(TAG, "URI opened: ${truncateUri(uri)}")
                Result.success(Unit)
            } catch (e: ActivityNotFoundException) {
                Logger.w(TAG, "No app found to handle URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalArgumentException("No app found to handle URI"))
            } catch (e: SecurityException) {
                Logger.w(TAG, "Permission denied opening URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalArgumentException("Permission denied: not allowed to open URI"))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Logger.e(TAG, "Unexpected error opening URI: ${truncateUri(uri)}", e)
                Result.failure(IllegalStateException("Failed to open URI unexpectedly"))
            }

        private fun putExtraWithInference(
            intent: Intent,
            key: String,
            value: Any?,
            typeOverride: String?,
        ) {
            try {
                if (typeOverride != null) {
                    putExtraWithTypeOverride(intent, key, value, typeOverride)
                } else {
                    putExtraWithAutoInference(intent, key, value)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                throw IllegalArgumentException("Failed to convert extra '$key': ${e.message}", e)
            }
        }

        private fun putExtraWithTypeOverride(
            intent: Intent,
            key: String,
            value: Any?,
            typeOverride: String,
        ) {
            require(value != null) {
                "Extra '$key' is null but type override '$typeOverride' requires a value"
            }
            when (typeOverride) {
                "string" -> intent.putExtra(key, value.toString())
                "int" -> intent.putExtra(key, (value as? Number)?.toInt() ?: value.toString().toInt())
                "long" -> intent.putExtra(key, (value as? Number)?.toLong() ?: value.toString().toLong())
                "float" -> intent.putExtra(key, (value as? Number)?.toFloat() ?: value.toString().toFloat())
                "double" -> intent.putExtra(key, (value as? Number)?.toDouble() ?: value.toString().toDouble())
                "boolean" -> {
                    val boolVal: Boolean = value as? Boolean ?: value.toString().toBooleanStrict()
                    intent.putExtra(key, boolVal)
                }
                else -> throw IllegalArgumentException(
                    "Unsupported extras_types value: '$typeOverride'. " +
                        "Supported: string, int, long, float, double, boolean",
                )
            }
        }

        private fun putExtraWithAutoInference(
            intent: Intent,
            key: String,
            value: Any?,
        ) {
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

        private fun putNumericExtra(
            intent: Intent,
            key: String,
            value: Number,
        ) {
            val doubleVal = value.toDouble()
            if (doubleVal % 1.0 == 0.0) {
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
                val flagValue =
                    flagMap[name]
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
            return if (pkg.isEmpty() || cls.isEmpty()) {
                Result.failure(
                    IllegalArgumentException(
                        "Invalid component format: '$component'. Package and class must not be empty",
                    ),
                )
            } else {
                Result.success(ComponentName(pkg, cls))
            }
        }

        companion object {
            private const val TAG = "MCP:IntentDispatcher"
            private const val TYPE_ACTIVITY = "activity"
            private const val TYPE_BROADCAST = "broadcast"
            private const val TYPE_SERVICE = "service"
            private const val FLAG_ACTIVITY_NEW_TASK = "FLAG_ACTIVITY_NEW_TASK"
            private val VALID_TYPES = setOf(TYPE_ACTIVITY, TYPE_BROADCAST, TYPE_SERVICE)

            private val flagMap: Map<String, Int> by lazy {
                Intent::class.java.fields
                    .filter { it.name.startsWith("FLAG_") && it.type == Int::class.javaPrimitiveType }
                    .associate { it.name to it.getInt(null) }
            }

            private fun truncateUri(uri: String): String =
                try {
                    val parsed = Uri.parse(uri)
                    val scheme = parsed.scheme ?: ""
                    val host = parsed.host ?: ""
                    if (host.isNotEmpty()) "$scheme://$host/..." else "$scheme:..."
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.d(TAG, "Failed to parse URI for truncation: ${e.message}")
                    "<malformed-uri>"
                }
        }
    }
