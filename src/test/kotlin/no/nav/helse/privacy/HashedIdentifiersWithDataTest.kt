package no.nav.helse.privacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import no.nav.helse.risk.JsonRisk
import org.junit.jupiter.api.Test
import kotlin.test.*

class HashedIdentifiersWithDataTest {

    @Test
    fun `hash en liste med Ider`() {
        val idAndData = listOf(
            "000000000" to buildJsonObject { put("info", JsonPrimitive(0)) },
            "110000000" to buildJsonObject { put("info", JsonPrimitive(1)) },
            "220000000" to buildJsonObject { put("info", JsonPrimitive(2)) },
            "330000000" to buildJsonObject { put("info", JsonPrimitive(3)) },
            "440000000" to buildJsonObject { put("info", JsonPrimitive(4)) },
            "220000000" to buildJsonObject { put("info", JsonPrimitive(5)) },
        )
        val sharedValue = "EnVerdiSomBådeProdusentenOgKonsumentenKjennerTil"
        val hashed = HashedIdentifiersWithData.fromIdAndDataPairs(idAndData, sharedValue = sharedValue)
        assertEquals(8333, hashed.iterationCount, "default 50000 delt på 6 elementer skal bli 8333")

        val somJsonString = JsonRisk.encodeToString(HashedIdentifiersWithData.serializer(JsonObject.serializer()), hashed)

        idAndData.forEach {
            assertFalse(somJsonString.contains(it.first), "Idene skal ikke finnes i klartekst")
        }
        assertFalse(somJsonString.contains(sharedValue), "sharedValue skal ikke finnes i klartekst")

        assertEquals(
            listOf(
                buildJsonObject { put("info", JsonPrimitive(2)) },
                buildJsonObject { put("info", JsonPrimitive(5)) },
            ),
            hashed.findAllById("220000000")
        )
        assertEquals(
            listOf(buildJsonObject { put("info", JsonPrimitive(0)) }),
            hashed.findAllById("000000000")
        )
        assertEquals(emptyList(), hashed.findAllById("990000000"))
        assertTrue(hashed.sharedValueIs(sharedValue))
        assertFalse(hashed.sharedValueIs("Feil Verdi"))
    }

    @Test
    fun `tom liste`() {
        val idAndData = emptyList<Pair<String, JsonObject>>()
        val sharedValue = "SomeSharedValue_123"
        HashedIdentifiersWithData.fromIdAndDataPairs(idAndData, sharedValue = sharedValue).apply {
            assertEquals(emptyList(), findAllById("220000000"))
            assertTrue(sharedValueIs(sharedValue))
            assertEquals(10000, iterationCount, "default 50000 delt på default 5 antattAntallSammenlikninger blir 10000")
        }
        HashedIdentifiersWithData.fromIdAndDataPairs(idAndData).apply {
            assertEquals(emptyList(), findAllById("220000000"))
            assertFalse(sharedValueIs(sharedValue))
            assertEquals(10000, iterationCount, "default 50000 delt på default 5 antattAntallSammenlikninger blir 10000")
        }
    }

    @Test
    fun `IdMasker skal fortsatt maskere id-Hasher, for de KAN fortsatt 'un-hashes' med brute-force`() {
        @Serializable
        data class NoeData(
            val tekst: String,
            val tall: Int,
        )
        @Serializable
        data class EnRespons(
            val etFelt: String,
            val idsAndData: HashedIdentifiersWithData<NoeData>
        )

        val idAndData = listOf(
            "000000000" to NoeData(tekst = "hei", tall = 42),
        )
        val hashed = HashedIdentifiersWithData.fromIdAndDataPairs(idAndData, sharedValue = "SOME_SHARED_VALUE")
        val hashedId = hashed.identifiersAndData.first().hashedId

        val enRespons = EnRespons(etFelt = "enVerdi", idsAndData = hashed)
        val somJsonElement = JsonRisk.encodeToJsonElement(EnRespons.serializer(), enRespons)
        val somStringUmaskert = JsonRisk.encodeToString(JsonElement.serializer(), somJsonElement)

        assertTrue(somStringUmaskert.contains(hashedId))

        val somStringMaskert = JsonRisk.encodeToString(JsonElement.serializer(), IdMasker().mask(somJsonElement))
        assertNotEquals(somStringMaskert, somStringUmaskert)
        assertFalse(somStringMaskert.contains(hashedId), "hashedId skal maskeres")

        val hashedIdMaskert = sha1(IdMasker.hashingsalt + hashedId).substring(0, 32) + "(32)"
        val hashedSharedValueMaskert = sha1(IdMasker.hashingsalt + hashed.sharedHashedValue!!).substring(0, 32) + "(32)"

        assertEquals(
            """{"etFelt":"enVerdi","idsAndData":{"algorithm":"PBKDF2WithHmacSHA512","iterationCount":10000,""" +
                    """"saltHexified":"${hashed.saltHexified}","identifiersAndData":[{"hashedId":"$hashedIdMaskert","data":{"tekst":"hei","tall":42}}],"sharedHashedValue":"$hashedSharedValueMaskert"}}""",
            somStringMaskert
        )
    }

    @Test
    fun `baseIterationCount 1 delt på antall må ikke bli 0`() {
        @Serializable
        data class NoeData(
            val tekst: String,
            val tall: Int,
        )
        val idAndData = listOf(
            "110000000" to NoeData(tekst = "en", tall = 1),
            "220000000" to NoeData(tekst = "to", tall = 2),
        )
        val hashed = HashedIdentifiersWithData.fromIdAndDataPairs(idAndData, baseIterationCount = 1)
        assertEquals(1, hashed.iterationCount)

        assertEquals(
            listOf(NoeData(tekst = "to", tall = 2)),
            hashed.findAllById("220000000")
        )
    }

    @Test
    fun `default antatt antall sammenlikninger styrer iteration count`() {
        @Serializable
        data class NoeData(
            val tekst: String,
            val tall: Int,
        )
        val idAndData = listOf(
            "110000000" to NoeData(tekst = "en", tall = 1),
            "220000000" to NoeData(tekst = "to", tall = 2),
        )
        val hashed = HashedIdentifiersWithData.fromIdAndDataPairs(idAndData)
        assertEquals(10000, hashed.iterationCount, "default 50000 delt på default 5 antattAntallSammenlikninger blir 10000")
    }

    @Test
    fun `JSON uten sharedHashedValue skal kunne deserialiseres`() {
        @Serializable
        data class NoeData(
            val tekst: String,
            val tall: Int,
        )
        val hashedIdsWithDataAsJsonWithoutSharedHashedValue = """
            {"algorithm":"PBKDF2WithHmacSHA512","iterationCount":10000,"saltHexified":"0b18e281c42b67921988d56c3082d1e8","identifiersAndData":[{"hashedId":"3a033c7a39f8588efe866703c67cc963","data":{"tekst":"en","tall":1}},{"hashedId":"22b5682fe9f4acfe4e1af15b36b6ec92","data":{"tekst":"to","tall":2}}]}
        """.trimIndent()
        val hashed = JsonRisk.decodeFromString(HashedIdentifiersWithData.serializer(NoeData.serializer()), hashedIdsWithDataAsJsonWithoutSharedHashedValue)

        assertNull(hashed.sharedHashedValue)

        assertEquals(listOf(NoeData(tekst = "to", tall = 2)), hashed.findAllById("220000000"))
    }

}


