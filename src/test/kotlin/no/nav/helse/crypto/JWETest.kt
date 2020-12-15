package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*
import javax.crypto.KeyGenerator
import kotlin.test.assertNotEquals


class JWETest {

    @Test
    fun `JsonObject encryption and decryption with AES-256`() {
        val someJson = json {
            "hei" to "duder"
            "data" to json {
                "liste" to jsonArray { +"heisann"; +"hoppsann" }
            }
        }

        val keygen = KeyGenerator.getInstance("AES")
        keygen.init(256)
        val key = keygen.generateKey()
        val keyBase64 = Base64URL.encode(key.encoded).toString()

        val jwkString = """
         {"kty":"oct",
          "kid":"smoppslag",
          "alg":"A256KW",
          "k":"$keyBase64"}
      """.trimIndent()

        val jwkSetString = """
         {"keys":[$jwkString]}
      """.trimIndent()

        val jwk = JWK.parse(jwkString)
        val jwkSet = JWKSet.parse(jwkSetString)
        println("JWKSet:")
        println(jwkSet.toJSONObject(false))

        assertEquals(jwk, jwkSet.keys.first())

        val jwe = someJson.encryptAsJWE(jwk.toJWKHolder())
        assertTrue(jwe.startsWith("ey"))

        val decryptedJson = JsonElement.decryptFromJWE(jwe, jwkSet.toJWKSetHolder()).jsonObject
        assertEquals(someJson, decryptedJson)
        println(decryptedJson.toString())
    }

    @Test
    fun `random AES JWK should not be hardcoded`() {
        val jwk1 = createRandomJWKAES()
        assertEquals(jwk1, jwk1)
        assertTrue(Arrays.equals(jwk1.toOctetSequenceKey().toByteArray(), jwk1.toOctetSequenceKey().toByteArray()))

        val jwk2 = createRandomJWKAES()
        assertNotEquals(jwk1, jwk2)
        assertFalse(Arrays.equals(jwk1.toOctetSequenceKey().toByteArray(), jwk2.toOctetSequenceKey().toByteArray()))
    }
}

