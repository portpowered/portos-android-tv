package com.portoperatingsystem.tv.auth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonObject

/** A JVM [DeviceSigner] for tests: same algorithm as the Keystore signer, key held in memory. */
internal class SoftwareSigner : DeviceSigner {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    override val publicJwk: JsonObject = Jose.jwk(keyPair.public as ECPublicKey)

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(data)
        Jose.derToJose(sign())
    }

    /** Verifies a compact JWS this signer produced. */
    fun verify(jws: String): Boolean {
        val (header, payload, signature) = jws.split('.')
        val raw = java.util.Base64.getUrlDecoder().decode(signature)
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(keyPair.public)
            update("$header.$payload".toByteArray())
            verify(raw)
        }
    }
}
