package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.KeyGenerator

class JWETest {

   @Test
   fun `JsonObject encryption and decryption with AES-256`() {
      val someJson = json {
         "hei" to "duder"
         "data" to json {
            "liste" to jsonArray { +"heisann"; +"hoppsann"}
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
      println(jwk)

      assertEquals(jwk, jwkSet.keys.first())

      val jwe = someJson.encryptAsJWE(jwk)
      assertTrue(jwe.startsWith("ey"))

      val decryptedJson = JsonElement.decryptFromJWE(jwe, jwkSet).jsonObject
      assertEquals(someJson, decryptedJson)
      println(decryptedJson.toString())
   }
}

