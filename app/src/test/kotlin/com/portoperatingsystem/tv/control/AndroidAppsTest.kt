package com.portoperatingsystem.tv.control

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class AndroidAppsTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val apps = AndroidApps(application)

    private fun query(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    private fun resolved(packageName: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Main"
        }
    }

    // Robolectric deprecated this shadow call but offers no equivalent that registers an intent's resolution directly.
    @Suppress("DEPRECATION")
    private fun installLauncher(packageName: String, category: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
        shadowOf(application.packageManager).addResolveInfoForIntent(intent, resolved(packageName))
        shadowOf(application.packageManager).addResolveInfoForIntent(query(category), resolved(packageName))
    }

    @Test
    fun launchesThroughTheLeanbackLauncherInANewTask() {
        installLauncher("com.netflix.ninja", Intent.CATEGORY_LEANBACK_LAUNCHER)

        assertTrue(apps.launch("com.netflix.ninja"))

        val started = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals("com.netflix.ninja", started.component?.packageName ?: started.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun fallsBackToTheRegularLauncher() {
        installLauncher("com.example.phoneapp", Intent.CATEGORY_LAUNCHER)

        assertTrue(apps.launch("com.example.phoneapp"))
        assertNotNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun refusesAPackageWithNoLauncherActivity() {
        assertFalse(apps.launch("com.example.missing"))
        assertEquals(null, shadowOf(application).nextStartedActivity)
    }

    @Test
    fun listsLaunchablePackagesOnceAndSorted() {
        installLauncher("com.spotify.tv.android", Intent.CATEGORY_LEANBACK_LAUNCHER)
        installLauncher("com.netflix.ninja", Intent.CATEGORY_LEANBACK_LAUNCHER)
        installLauncher("com.netflix.ninja", Intent.CATEGORY_LAUNCHER)

        assertEquals(listOf("com.netflix.ninja", OWN_PACKAGE, "com.spotify.tv.android"), apps.launchable())
    }

    @Test
    fun leavesOutNamesThatAreNotPackageNames() {
        installLauncher("android", Intent.CATEGORY_LAUNCHER)

        assertEquals(listOf(OWN_PACKAGE), apps.launchable())
    }

    private companion object {
        // The app is itself a Leanback launcher activity, and its manifest is part of the test environment.
        const val OWN_PACKAGE = "com.portoperatingsystem.tv"
    }
}
