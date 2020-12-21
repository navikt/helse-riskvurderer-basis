package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.test.assertEquals

class JWKHolderTest {

    val tempDir = System.getProperty("java.io.tmpdir")
    val tempDirPath = Paths.get(tempDir)

    @Test
    fun `JWK and JWKS is updated when files are updated on disk`() {
        val jwk1 = lagEnJWK("key-1")
        val jwks1 = JWKSet(jwk1)
        val jsonJWK1 = jwk1.toJSONObject().toString()
        val jsonJWKS1 = jwks1.toJSONObject(false).toString()

        val jwkPath: Path = tempDirPath.resolve(UUID.randomUUID().toString())
        val jwksPath: Path = tempDirPath.resolve(UUID.randomUUID().toString())

        jsonJWK1.writeToFile(jwkPath)
        jsonJWKS1.writeToFile(jwksPath)

        val jwk2 = lagEnJWK("key-2")
        val jwks1and2 = JWKSet(listOf(jwk1, jwk2))
        val jsonJWK2 = jwk2.toJSONObject().toString()
        val jsonJWKS1and2 = jwks1and2.toJSONObject(false).toString()

        val jwkHolder = JWKHolder.fromDynamicFile(jwkPath)
        val jwksHolder = JWKSetHolder.fromDynamicFile(jwksPath)

        val payload1 = buildJsonObject {
            put("felt", "verdi")
        }

        val encrypted1 = payload1.encryptAsJWE(jwkHolder)
        assertEquals(payload1, JsonElement.decryptFromJWE(encrypted1, jwksHolder))

        jsonJWK2.writeToFile(jwkPath) // Overwriting JWK referred by jwkHolder
        val encrypted2 = payload1.encryptAsJWE(jwkHolder)
        assertThrows<RuntimeException>("jwksHolder should no longer contain correct key") {
            JsonElement.decryptFromJWE(encrypted2, jwksHolder)
        }

        jsonJWKS1and2.writeToFile(jwksPath) // Write new JWKS with both key-1 and key-2 to disk
        assertEquals(
            payload1,
            JsonElement.decryptFromJWE(encrypted2, jwksHolder),
            "we should be able to decrypt encrypted2 with updated JWKS"
        )
        assertEquals(
            payload1,
            JsonElement.decryptFromJWE(encrypted1, jwksHolder),
            "we should also be able to decrypt OLD encrypted1 with updated JWKS"
        )
    }

    @Test
    fun `JWKSet from multiple other JWKSets`() {
        val jwk1 = lagEnJWK("key-1")
        val jwks1 = JWKSet(jwk1)
        val jsonJWK1 = jwk1.toJSONObject().toString()
        val jsonJWKS1 = jwks1.toJSONObject(false).toString()
        val jwk1Path: Path = tempDirPath.resolve(UUID.randomUUID().toString())
        val jwks1Path: Path = tempDirPath.resolve(UUID.randomUUID().toString())
        jsonJWK1.writeToFile(jwk1Path)
        jsonJWKS1.writeToFile(jwks1Path)

        val jwk2 = lagEnJWK("key-2")
        val jwks2 = JWKSet(jwk2)
        val jsonJWK2 = jwk2.toJSONObject().toString()
        val jsonJWKS2 = jwks2.toJSONObject(false).toString()
        val jwk2Path: Path = tempDirPath.resolve(UUID.randomUUID().toString())
        val jwks2Path: Path = tempDirPath.resolve(UUID.randomUUID().toString())
        jsonJWK2.writeToFile(jwk2Path)
        jsonJWKS2.writeToFile(jwks2Path)

        val jwk1Holder = JWKHolder.fromDynamicFile(jwk1Path)
        val jwks1Holder = JWKSetHolder.fromDynamicFile(jwks1Path)

        val jwk2Holder = JWKHolder.fromDynamicFile(jwk2Path)
        val jwks2Holder = JWKSetHolder.fromDynamicFile(jwks2Path)

        val jwksALLHolder = JWKSetHolder.fromMultiple(
            JWKSetHolder.fromDynamicFile(jwks1Path),
            JWKSetHolder.fromDynamicFile(jwks2Path)
        )

        val payload1 = buildJsonObject { put("felt", "verdi") }
        val payload2 = buildJsonObject { put("felt", "verdi-nummer2") }

        val encrypted1 = payload1.encryptAsJWE(jwk1Holder)
        val encrypted2 = payload2.encryptAsJWE(jwk2Holder)

        assertEquals(payload1, JsonElement.decryptFromJWE(encrypted1, jwks1Holder))
        assertThrows<RuntimeException> { JsonElement.decryptFromJWE(encrypted1, jwks2Holder) }
        assertEquals(payload1, JsonElement.decryptFromJWE(encrypted1, jwksALLHolder))

        assertThrows<RuntimeException> { JsonElement.decryptFromJWE(encrypted2, jwks1Holder) }
        assertEquals(payload2, JsonElement.decryptFromJWE(encrypted2, jwks2Holder))
        assertEquals(payload2, JsonElement.decryptFromJWE(encrypted2, jwksALLHolder))

        val payload3 = buildJsonObject { put("felt", "verdi-nummer-TRE") }

        val jwk3 = lagEnJWK("key-3")
        val jwks1and3 = JWKSet(listOf(jwk1, jwk3))
        val jwk3Holder = jwk3.toJWKHolder()
        val jsonJWKS1and3 = jwks1and3.toJSONObject(false).toString()

        val encrypted3 = payload3.encryptAsJWE(jwk3Holder)
        assertThrows<RuntimeException> { JsonElement.decryptFromJWE(encrypted3, jwksALLHolder) }
        jsonJWKS1and3.writeToFile(jwks1Path) // Writing new JWKS to jwks-1-path should fix also the "multi-holder":
        assertEquals(
            payload3,
            JsonElement.decryptFromJWE(encrypted3, jwksALLHolder),
            "ALL-holder whould get updated key-3 from jwks1Path"
        )
        assertEquals(payload2, JsonElement.decryptFromJWE(encrypted2, jwksALLHolder), "key-2 should still work")
        assertEquals(
            payload1,
            JsonElement.decryptFromJWE(encrypted1, jwksALLHolder),
            "and key-1 should also still work"
        )
    }

    private fun String.writeToFile(path: Path) {
        Files.writeString(
            path,
            this,
            Charset.defaultCharset(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

}