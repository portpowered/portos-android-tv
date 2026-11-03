package com.portoperatingsystem.tv.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** What a paired TV remembers. The refresh token is DPoP-bound, so it is useless without the Keystore key. */
internal data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtSeconds: Long,
    val authLinkId: String?
)

/** App-private storage for the device id and credentials. Excluded from backup (data_extraction_rules). */
internal class CredentialStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A stable id for this TV, used as its Port OS route id. */
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun load(): Credentials? {
        val access = preferences.getString(KEY_ACCESS, null)
        val refresh = preferences.getString(KEY_REFRESH, null)
        if (access == null || refresh == null) return null
        return Credentials(access, refresh, preferences.getLong(KEY_EXPIRES, 0), preferences.getString(KEY_LINK, null))
    }

    fun save(credentials: Credentials) {
        preferences.edit()
            .putString(KEY_ACCESS, credentials.accessToken)
            .putString(KEY_REFRESH, credentials.refreshToken)
            .putLong(KEY_EXPIRES, credentials.accessTokenExpiresAtSeconds)
            .putString(KEY_LINK, credentials.authLinkId)
            .apply()
    }

    /** Forgets the pairing but keeps the device id, so re-pairing keeps the same route identity. */
    fun clear() {
        preferences.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_LINK).apply()
    }

    private companion object {
        const val FILE = "portos-credentials"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "access_expires_at"
        const val KEY_LINK = "auth_link_id"
    }
}
