package com.danielealbano.androidremotecontrolmcp.services.apps

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Default implementation of [AppManager] backed by Android's [PackageManager]
 * and [ActivityManager].
 */
@Suppress("TooGenericExceptionCaught")
class AppManagerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppManager {
        override suspend fun listInstalledApps(
            filter: AppFilter,
            nameQuery: String?,
        ): List<AppInfo> {
            val pm = context.packageManager
            val applications = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            return applications
                .asSequence()
                .filter { appInfo ->
                    when (filter) {
                        AppFilter.ALL -> true
                        AppFilter.USER -> (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                        AppFilter.SYSTEM -> (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    }
                }
                .filter { appInfo ->
                    if (nameQuery != null) {
                        pm.getApplicationLabel(appInfo).toString().contains(nameQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .map { appInfo ->
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val (versionName, versionCode) =
                        try {
                            val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                            packageInfo.versionName to PackageInfoCompat.getLongVersionCode(packageInfo)
                        } catch (_: PackageManager.NameNotFoundException) {
                            null to 0L
                        }
                    AppInfo(
                        packageId = appInfo.packageName,
                        name = name,
                        versionName = versionName,
                        versionCode = versionCode,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList()
        }

        override suspend fun openApp(packageId: String): Result<Unit> =
            try {
                val intent =
                    context.packageManager.getLaunchIntentForPackage(packageId)
                        ?: return Result.failure(
                            IllegalArgumentException("No launchable activity found for package '$packageId'"),
                        )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched application: $packageId")
                Result.success(Unit)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Activity not found for package: $packageId", e)
                Result.failure(e)
            }

        override suspend fun closeApp(packageId: String): Result<Unit> {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(packageId)
            Log.i(TAG, "Requested kill of background processes for: $packageId")
            return Result.success(Unit)
        }

        companion object {
            private const val TAG = "MCP:AppManager"
        }
    }
