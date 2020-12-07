package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
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

fun main() {
   val base = "oppslagsdata"
   val jwk = lagEnJWK("$base-key-001")
   val jwks = JWKSet(jwk)
   val jwkString = jwk.toJSONObject().toString()
   val jwksString = jwks.toJSONObject(false).toString()
   println("Create JWK:")
   val createJWKCommand = "kubectl create secret generic $base-send --from-literal=${base}_jwk='$jwkString'"
   println(createJWKCommand)
   println("Create JWKS:")
   val createJWKSCommand = "kubectl create secret generic $base-receive --from-literal=${base}_jwks='$jwksString'"
   println(createJWKSCommand)
   println("Read JWK:")
   println("kubectl get secret oppslagsdata-send -o yaml")
   println("echo '<base64stuff>' | base64 --decode")
   println("Update JWKS:")
   println("!NB!TODO: You might want to include _old_ JWKS-keys also, in the new JWKS, and update JWKS _before_ JWK")
   println("Force Update JWK:")
   println("$createJWKCommand --dry-run -o yaml | kubectl apply -f -")
   val naisYamlSend = """   filesFrom:
      - secret: $base-send"""
   val naisYamlReceive = """   filesFrom:
      - secret: $base-receive"""
   println("Sender: ")
   println("NAIS spec:")
   println(naisYamlSend)
   println("""Kotlin-App:
      encryptionJWK = JWKHolder.fromSecret("${base}_jwk")
   """.trimMargin())
   println("Receiver: ")
   println("NAIS spec:")
   println(naisYamlReceive)
   println("""Kotlin-App:
      decryptionJWKS = JWKSetHolder.fromSecret("${base}_jwks")
   """.trimMargin())
}
