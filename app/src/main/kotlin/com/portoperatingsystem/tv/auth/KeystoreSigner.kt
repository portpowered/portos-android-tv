package com.portoperatingsystem.tv.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonObject

/**
 * The TV's identity key: a non-exportable P-256 key in the Android Keystore, created on first use.
 * Pairing binds the refresh token (DPoP) and the plugin installation to it.
 */
internal class KeystoreSigner(private val alias: String = DEFAULT_ALIAS) : DeviceSigner {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override val publicJwk: JsonObject
        get() = Jose.jwk(keyStore.getCertificate(ensureKey()).publicKey as ECPublicKey)

    override fun sign(data: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(ensureKey(), null) as PrivateKey
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return Jose.derToJose(der)
    }

    /** Deletes the key; the next use creates a new identity and the TV must pair again. */
    fun reset() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun ensureKey(): String {
        if (!keyStore.containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
        return alias
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "portos-device"
    }
}
