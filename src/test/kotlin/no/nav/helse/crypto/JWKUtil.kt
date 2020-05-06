package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.util.Base64URL
import javax.crypto.KeyGenerator


fun lagEnJWK(kid: String = "oppslagsnoekkel-001"): JWK {
   val keygen = KeyGenerator.getInstance("AES")
   keygen.init(256)
   val key = keygen.generateKey()
   val keyBase64 = Base64URL.encode(key.encoded).toString()
   val jwkString = """
         {"kty":"oct",
          "kid":"$kid",
          "alg":"A256KW",
          "k":"$keyBase64"}
      """.trimIndent()

   return JWK.parse(jwkString)
}
