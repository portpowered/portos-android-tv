package com.portoperatingsystem.tv.auth

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Signs with this TV's P-256 key. The production signer lives in the Android Keystore. */
internal interface DeviceSigner {
    /** The public key, as a JWK (`kty`, `crv`, `x`, `y`). */
    val publicJwk: JsonObject

    /** Signs [data] with ES256 and returns the 64-byte JOSE signature (R || S). */
    fun sign(data: ByteArray): ByteArray
}

/** Compact JWS and DPoP proofs, ES256 only. */
internal object Jose {
    private const val COORDINATE_BYTES = 32
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun base64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** The JWK of a P-256 public key. */
    fun jwk(key: ECPublicKey): JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", base64Url(fixedLength(key.w.affineX)))
        put("y", base64Url(fixedLength(key.w.affineY)))
    }

    /** A compact JWS over [claims], with [header] fields added to `alg: ES256`. */
    fun sign(signer: DeviceSigner, claims: JsonObject, header: JsonObject = JsonObject(emptyMap())): String {
        val fullHeader = JsonObject(header + ("alg" to JsonPrimitive("ES256")))
        val input = base64Url(fullHeader.toString().toByteArray()) + "." + base64Url(claims.toString().toByteArray())
        return input + "." + base64Url(signer.sign(input.toByteArray()))
    }

    /** An RFC 9449 DPoP proof for one request. */
    fun dpopProof(signer: DeviceSigner, method: String, url: String, nowSeconds: Long): String {
        val header = buildJsonObject {
            put("typ", "dpop+jwt")
            put("jwk", signer.publicJwk)
        }
        val claims = buildJsonObject {
            put("jti", UUID.randomUUID().toString())
            put("htm", method)
            put("htu", url.substringBefore('?').substringBefore('#'))
            put("iat", nowSeconds)
        }
        return sign(signer, claims, header)
    }

    /** Converts a DER ECDSA signature (what `SHA256withECDSA` returns) to JOSE's fixed R || S form. */
    fun derToJose(der: ByteArray): ByteArray {
        require(der.size > DER_MIN_SIZE && der[0] == DER_SEQUENCE) { "not a DER ECDSA signature" }
        var offset = if (der[1].toInt() and DER_LONG_FORM != 0) DER_LONG_HEADER else DER_SHORT_HEADER
        val r = readInteger(der, offset)
        offset += INTEGER_HEADER + r.size
        val s = readInteger(der, offset)
        return fixedLength(BigInteger(1, r)) + fixedLength(BigInteger(1, s))
    }

    private const val DER_SEQUENCE: Byte = 0x30
    private const val DER_INTEGER: Byte = 0x02
    private const val DER_LONG_FORM = 0x80
    private const val DER_SHORT_HEADER = 2
    private const val DER_LONG_HEADER = 3
    private const val DER_MIN_SIZE = 8
    private const val INTEGER_HEADER = 2

    private fun readInteger(der: ByteArray, offset: Int): ByteArray {
        require(der[offset] == DER_INTEGER) { "malformed DER integer" }
        val length = der[offset + 1].toInt()
        return der.copyOfRange(offset + INTEGER_HEADER, offset + INTEGER_HEADER + length)
    }

    private fun fixedLength(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(bytes.size <= COORDINATE_BYTES) { "value does not fit P-256" }
        return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
    }
}
