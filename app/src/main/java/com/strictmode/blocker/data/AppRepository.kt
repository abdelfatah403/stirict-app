package com.strictmode.blocker.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

object AppRepository {

    /**
     * Queries all launchable apps on the device, excluding StrictLock itself,
     * the default Home Launcher, and critical system settings (which are automatically
     * protected by the Anti-Cheat engine during a lock session).
     */
    suspend fun getLaunchableApps(context: Context): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val myPackage = context.packageName
            val homePackages = getHomeLauncherPackages(pm)

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)

            resolveInfos
                .mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkgName == myPackage || pkgName in homePackages || pkgName == "com.android.settings") {
                        return@mapNotNull null
                    }
                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
                        .ifEmpty { pkgName }
                    val icon = runCatching { resolveInfo.loadIcon(pm) }.getOrNull()
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    InstalledAppInfo(
                        appName = label,
                        packageName = pkgName,
                        icon = icon,
                        isSystemApp = isSystem
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appName.lowercase() }
        }

    private fun getHomeLauncherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }
}
