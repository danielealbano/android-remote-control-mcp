package com.danielealbano.androidremotecontrolmcp.services.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped cache for app icons.
 *
 * Preloads the first [PRELOAD_COUNT] launchable app icons at app startup
 * so they're ready before the user reaches the notification filter screen.
 * Remaining icons are loaded on demand and cached for the app's lifetime.
 */
@Singleton
class AppIconCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val cache = ConcurrentHashMap<String, Bitmap>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var launchableApps: List<Pair<String, String>> = emptyList()

        /**
         * Preloads the launchable app list and icons for the first [count] apps
         * (alphabetically). Called from [com.danielealbano.androidremotecontrolmcp.McpApplication.onCreate].
         */
        fun preload(count: Int = PRELOAD_COUNT) {
            scope.launch {
                val pm = context.packageManager
                val apps = getLaunchableAppsSorted(pm)
                launchableApps = apps
                for (app in apps.take(count)) {
                    loadAndCache(pm, app.first)
                }
                Logger.d(TAG, "Preloaded ${apps.size} apps, ${cache.size} icons")
            }
        }

        /**
         * Returns the cached list of launchable apps as (packageId, appName) pairs,
         * sorted alphabetically. If preload hasn't completed, fetches synchronously.
         */
        fun getLaunchableApps(): List<Pair<String, String>> {
            if (launchableApps.isNotEmpty()) return launchableApps
            val apps = getLaunchableAppsSorted(context.packageManager)
            launchableApps = apps
            return apps
        }

        /**
         * Returns the cached icon for [packageId], or null if not yet loaded.
         */
        operator fun get(packageId: String): Bitmap? = cache[packageId]

        /**
         * Returns the full icon cache as an immutable map.
         */
        fun getAll(): Map<String, Bitmap> = cache.toMap()

        /**
         * Re-queries PackageManager for the current launchable app list, diffs against
         * the cached list, adds new apps, removes uninstalled apps, and loads icons
         * for any uncached entries. Calls [onUpdated] when the list or icons change
         * so the UI can recompose.
         */
        fun refresh(onUpdated: () -> Unit = {}) {
            scope.launch {
                val pm = context.packageManager
                val freshApps = getLaunchableAppsSorted(pm)
                val freshIds = freshApps.map { it.first }.toSet()
                val cachedIds = launchableApps.map { it.first }.toSet()

                // Remove uninstalled apps from icon cache
                for (removedId in cachedIds - freshIds) {
                    cache.remove(removedId)
                }

                // Update the app list
                launchableApps = freshApps

                // Emit immediately so the UI shows the updated list
                onUpdated()

                // Load icons for any uncached apps (new installs + previously unloaded)
                val uncached = freshApps.filter { !cache.containsKey(it.first) }
                if (uncached.isNotEmpty()) {
                    for (chunk in uncached.chunked(LOAD_CHUNK_SIZE)) {
                        for (app in chunk) {
                            loadAndCache(pm, app.first)
                        }
                        onUpdated()
                    }
                }
            }
        }

        private fun loadAndCache(
            pm: PackageManager,
            packageId: String,
        ) {
            if (cache.containsKey(packageId)) return
            try {
                val bitmap = pm.getApplicationIcon(packageId).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                cache[packageId] = bitmap
            } catch (_: Exception) {
                // App may have been uninstalled or icon unavailable
            }
        }

        /**
         * Returns launchable apps sorted alphabetically by name.
         * Each pair is (packageId, appName).
         */
        private fun getLaunchableAppsSorted(pm: PackageManager): List<Pair<String, String>> {
            val launchIntent =
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
            val resolveInfos =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launchIntent, 0)
                }
            return resolveInfos
                .mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm)?.toString() ?: return@mapNotNull null
                    if (label.isBlank() || label == pkgName) return@mapNotNull null
                    pkgName to label
                }.distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }

        companion object {
            private const val TAG = "MCP:AppIconCache"
            private const val PRELOAD_COUNT = 10
            private const val LOAD_CHUNK_SIZE = 10
            private const val ICON_SIZE_PX = 96
        }
    }
