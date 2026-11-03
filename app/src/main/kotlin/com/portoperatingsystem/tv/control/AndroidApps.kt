package com.portoperatingsystem.tv.control

import android.content.Context
import android.content.Intent
import com.portoperatingsystem.tv.protocol.command.ApplicationIds

/**
 * Opens and lists the TV's installed apps. Package visibility (Android 11+) hides apps the manifest's `<queries>` does
 * not name, so the manifest declares the two launcher intents this class resolves.
 */
internal class AndroidApps(private val context: Context) : AppPort {
    override fun launch(packageName: String): Boolean {
        val packages = context.packageManager
        val intent = packages.getLeanbackLaunchIntentForPackage(packageName)
            ?: packages.getLaunchIntentForPackage(packageName)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun launchable(): List<String> = LAUNCHER_CATEGORIES
        .flatMap { category ->
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            context.packageManager.queryIntentActivities(query, 0).map { it.activityInfo.packageName }
        }
        .filter(ApplicationIds::isValid)
        .distinct()
        .sorted()

    private companion object {
        val LAUNCHER_CATEGORIES = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
    }
}
