package com.portoperatingsystem.tv.control

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Whether the owner has switched the accessibility service on, read from the system settings `adb` writes. */
internal object AccessibilitySetting {
    private const val SEPARATOR = ':'

    fun isEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled && lists(services, ComponentName(context, PortOsAccessibilityService::class.java))
    }

    /** Whether the colon-separated [setting] names [service], in either the short or the flattened form. */
    fun lists(setting: String?, service: ComponentName): Boolean =
        setting.orEmpty().split(SEPARATOR).any { ComponentName.unflattenFromString(it) == service }
}
