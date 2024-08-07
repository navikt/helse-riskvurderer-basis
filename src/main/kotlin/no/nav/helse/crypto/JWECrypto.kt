package no.nav.helse.crypto

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.AESDecrypter
import com.nimbusds.jose.crypto.AESEncrypter
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64
import com.nimbusds.jose.util.Base64URL
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.nav.helse.risk.JsonRisk
import java.net.URI
import java.util.*
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private val json = JsonRisk

fun JsonElement.Companion.decryptFromJWE(jwe: String, jwks: JWKSetHolder): JsonElement {
   return json.parseToJsonElement(decryptJWE(jwe, jwks.jwkSet()))
}

fun JsonElement.encryptAsJWE(jwk: JWKHolder): String {
   return encryptAsJWE(toString().toByteArray(charset = Charsets.UTF_8), jwk.jwk())!!
}

internal fun decryptJWE(jweString: String, jwkSet: JWKSet): String {
   val jwe = JWEObject.parse(jweString)
   val keyId = jwe.header.keyID
   val jwk: JWK = jwkSet.getKeyByKeyId(keyId) ?: throw RuntimeException("No decryption key found with keyId=$keyId")
   val jweDecrypter: JWEDecrypter
   jweDecrypter = if (jwk is OctetSequenceKey) {
      AESDecrypter(jwk)
   } else if (jwk is RSAKey) {
      RSADecrypter((jwk).toRSAPrivateKey())
   } else {
      throw RuntimeException("Unrecognized JWK type: " + jwk.javaClass.simpleName)
   }
   jwe.decrypt(jweDecrypter)
   return String(jwe.payload.toBytes(), Charsets.UTF_8)
}

internal fun encryptAsJWE(content: ByteArray, jwk: JWK): String? {
   val jweEncrypter: JWEEncrypter
   val jweAlgorithm: JWEAlgorithm
   if (jwk is OctetSequenceKey) {
      jweEncrypter = AESEncrypter(jwk)
      jweAlgorithm = JWEAlgorithm.A256KW
   } else if (jwk is RSAKey) {
      jweEncrypter = RSAEncrypter(jwk.toRSAPublicKey())
      jweAlgorithm = JWEAlgorithm.RSA_OAEP_256
   } else {
      throw RuntimeException("Unrecognized JWK type: " + jwk.javaClass.simpleName)
   }
   val header = JWEHeader(jweAlgorithm,
       EncryptionMethod.A256GCM,
       null as JOSEObjectType?, null as String?, null as MutableSet<String>?, null as URI?, null as JWK?, null as URI?, null as Base64URL?, null as Base64URL?, null as MutableList<Base64>?,
       jwk.keyID,
       null as JWK?,
       CompressionAlgorithm.DEF,  // Compress before encryption, because encrypted data cannot be compressed
       null as Base64URL?, null as Base64URL?, null as Base64URL?, 0,
       null as Base64URL?, null as Base64URL?, null as String?, null as MutableMap<String, Any>?, null as Base64URL?)
   val jwe = JWEObject(header, Payload(content))
   jwe.encrypt(jweEncrypter)
   return jwe.serialize()
}

internal fun jwkSecretKeyFrom(kid: String, key: SecretKey): JWK {
   val keyBase64 = Base64URL.encode(key.encoded).toString()
   val jwkJson = buildJsonObject {
      put("kty", "oct")
      put("kid", kid)
      put("alg", "A256KW")
      put("k", keyBase64)
   }
   return JWK.parse(jwkJson.toString())
}

internal fun createRandomJWKAES(kid: String = UUID.randomUUID().toString()): JWK {
   val keygen = KeyGenerator.getInstance("AES")
   keygen.init(256)
   val key = keygen.generateKey()
   return jwkSecretKeyFrom(kid, key)
}
