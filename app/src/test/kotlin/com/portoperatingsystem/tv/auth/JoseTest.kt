package com.portoperatingsystem.tv.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JoseTest {
    private val signer = SoftwareSigner()

    private fun part(jws: String, index: Int) =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jws.split('.')[index]))).jsonObject

    @Test
    fun signsVerifiableEs256Jws() {
        val jws = Jose.sign(signer, buildJsonObject { put("sub", "tv") }, buildJsonObject { put("typ", "JWT") })
        assertTrue(signer.verify(jws))
        assertEquals("ES256", part(jws, 0)["alg"]?.jsonPrimitive?.content)
        assertEquals("JWT", part(jws, 0)["typ"]?.jsonPrimitive?.content)
        assertEquals("tv", part(jws, 1)["sub"]?.jsonPrimitive?.content)
    }

    @Test
    fun signaturesAreAlwaysSixtyFourBytes() {
        repeat(50) {
            assertEquals(64, signer.sign("payload $it".toByteArray()).size)
        }
    }

    @Test
    fun dpopProofCarriesKeyAndStripsQuery() {
        val proof = Jose.dpopProof(
            signer,
            "POST",
            "https://api.example.com/auth/token?x=1#f",
            nowSeconds = 1_700_000_000
        )
        assertTrue(signer.verify(proof))
        val header = part(proof, 0)
        assertEquals("dpop+jwt", header["typ"]?.jsonPrimitive?.content)
        assertEquals(signer.publicJwk, header["jwk"]?.jsonObject)
        val claims = part(proof, 1)
        assertEquals("POST", claims["htm"]?.jsonPrimitive?.content)
        assertEquals("https://api.example.com/auth/token", claims["htu"]?.jsonPrimitive?.content)
        assertEquals("1700000000", claims["iat"]?.jsonPrimitive?.content)
    }

    @Test
    fun jwkHasFixedLengthCoordinates() {
        val jwk = signer.publicJwk
        assertEquals("EC", jwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", jwk["crv"]?.jsonPrimitive?.content)
        assertEquals(32, Base64.getUrlDecoder().decode(jwk["x"]?.jsonPrimitive?.content).size)
        assertEquals(32, Base64.getUrlDecoder().decode(jwk["y"]?.jsonPrimitive?.content).size)
    }
}
