package com.portoperatingsystem.tv.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JoseTest {
    private val signer = SoftwareSigner()

    private fun part(jws: String, index: Int) =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jws.split('.')[index]))).jsonObject

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
        assertEquals("ES256", header["alg"]?.jsonPrimitive?.content)
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
