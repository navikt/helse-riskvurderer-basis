package no.nav.helse.risk.cache

import com.nimbusds.jose.JWEObject
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InMemoryLookupCacheTest {

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
        fnrCount.clear()
    }

    val fnrCount = mutableMapOf<String, Int>()
    private fun someLookupFunc(fnr: String, fom: LocalDate, tom: LocalDate): List<JsonObject>? {
        fnrCount[fnr] = (fnrCount[fnr] ?: 0) + 1
        if (fnr.startsWith("1")) return null
        return listOf(
            buildJsonObject {
                put("fnr", fnr)
                put("rec", 1)
                put("date", fom.toString())
            },
            buildJsonObject {
                put("fnr", fnr)
                put("rec", 2)
                put("date", tom.toString())
            }
        )
    }

    val fom = LocalDate.now().minusDays(3)
    val tom = LocalDate.now()

    @Test
    fun `some simple calls`() {
        val cache = InMemoryLookupCache(
            serializer = ListSerializer(JsonObject.serializer()),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )

        for (i in 1..5) {
            assertNull(cache.cachedLookup(::someLookupFunc, "1111", fom, tom))
        }
        assertEquals(5, fnrCount["1111"], "NULL-responses are not cached")

        "2222".let { fnr ->
            validate5Times(cache, fnr)
            assertEquals(1, fnrCount[fnr])
        }
        "090909".let { fnr ->
            validate5Times(cache, fnr)
            assertEquals(1, fnrCount[fnr])
        }
        "2222".let { fnr ->
            validate5Times(cache, fnr)
            assertEquals(1, fnrCount[fnr], "should still be only 1 total")
        }
    }

    private fun f1(p1: String): JsonObject = buildJsonObject { put("params", buildJsonArray { add(p1) }) }
    private fun f2(p1: String, p2: String): JsonObject =
        buildJsonObject { put("params", buildJsonArray { add(p1); add(p2) }) }

    private fun f3(p1: String, p2: String, p3: String): JsonObject =
        buildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3) }) }

    private fun f4(p1: String, p2: String, p3: String, p4: String): JsonObject =
        buildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3); add(p4) }) }

    private fun f5(p1: String, p2: String, p3: String, p4: String, p5: String): JsonObject =
        buildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3); add(p4); add(p5) }) }

    private suspend fun sf1(p1: String): JsonObject =
        suspendBuildJsonObject { put("params", buildJsonArray { add(p1) }) }

    private suspend fun sf2(p1: String, p2: String): JsonObject =
        suspendBuildJsonObject { put("params", buildJsonArray { add(p1); add(p2) }) }

    private suspend fun sf3(p1: String, p2: String, p3: String): JsonObject =
        suspendBuildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3) }) }

    private suspend fun sf4(p1: String, p2: String, p3: String, p4: String): JsonObject =
        suspendBuildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3); add(p4) }) }

    private suspend fun sf5(p1: String, p2: String, p3: String, p4: String, p5: String): JsonObject =
        suspendBuildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3); add(p4); add(p5) }) }

    @Test
    fun `check all function-variants`() {
        val cache = InMemoryLookupCache(
            serializer = JsonObject.serializer(),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )
        for (i in 1..10) {
            assertEquals(jsonArrayOf("1"), cache.cachedLookup(::f1, "1")!!["params"])
        }
        assertEquals(1, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(jsonArrayOf("1", "2"), cache.cachedLookup(::f2, "1", "2")!!["params"])
        }
        assertEquals(2, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(jsonArrayOf("1", "2", "3"), cache.cachedLookup(::f3, "1", "2", "3")!!["params"])
        }
        assertEquals(3, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(jsonArrayOf("1", "2", "3", "4"), cache.cachedLookup(::f4, "1", "2", "3", "4")!!["params"])
        }
        assertEquals(4, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(
                jsonArrayOf("1", "2", "3", "4", "5"),
                cache.cachedLookup(::f5, "1", "2", "3", "4", "5")!!["params"]
            )
        }
        assertEquals(5, cache.cache.estimatedSize())

        for (i in 1..10) {
            assertEquals(jsonArrayOf("1"), runBlocking { cache.cachedLookup(::sf1, "1")!!["params"] })
        }
        assertEquals(6, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(jsonArrayOf("1", "2"), runBlocking { cache.cachedLookup(::sf2, "1", "2")!!["params"] })
        }
        assertEquals(7, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(
                jsonArrayOf("1", "2", "3"),
                runBlocking { cache.cachedLookup(::sf3, "1", "2", "3")!!["params"] })
        }
        assertEquals(8, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(
                jsonArrayOf("1", "2", "3", "4"),
                runBlocking { cache.cachedLookup(::sf4, "1", "2", "3", "4")!!["params"] })
        }
        assertEquals(9, cache.cache.estimatedSize())
        for (i in 1..10) {
            assertEquals(
                jsonArrayOf("1", "2", "3", "4", "5"),
                runBlocking { cache.cachedLookup(::sf5, "1", "2", "3", "4", "5")!!["params"] })
        }
        assertEquals(10, cache.cache.estimatedSize())
    }

    @Test
    fun `request-hash-calculation must not forget or mix up any parameters`() {
        val cache = InMemoryLookupCache(
            serializer = JsonObject.serializer(),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )
        val mutations = listOf(
            listOf("_", "_", "_", "_", "_"),
            listOf("_", "X", "_", "_", "_"),
            listOf("_", "_", "X", "_", "_"),
            listOf("_", "_", "_", "X", "_"),
            listOf("_", "_", "_", "_", "X")
        )
        for (midx in 0..4) {
            assertEquals(
                jsonArrayOf(*mutations[midx].toTypedArray()),
                cache.cachedLookup(
                    ::f5,
                    mutations[midx][0],
                    mutations[midx][1],
                    mutations[midx][2],
                    mutations[midx][3],
                    mutations[midx][4]
                )!!["params"]
            )
        }
        assertEquals(5, cache.cache.estimatedSize())

        for (midx in 0..3) {
            assertEquals(
                jsonArrayOf(*mutations[midx].slice(0..3).toTypedArray()),
                cache.cachedLookup(
                    ::f4,
                    mutations[midx][0],
                    mutations[midx][1],
                    mutations[midx][2],
                    mutations[midx][3]
                )!!["params"]
            )
        }
        assertEquals(5 + 4, cache.cache.estimatedSize())

        for (midx in 0..2) {
            assertEquals(
                jsonArrayOf(*mutations[midx].slice(0..2).toTypedArray()),
                cache.cachedLookup(::f3, mutations[midx][0], mutations[midx][1], mutations[midx][2])!!["params"]
            )
        }
        assertEquals(5 + 4 + 3, cache.cache.estimatedSize())

        for (midx in 0..1) {
            assertEquals(
                jsonArrayOf(*mutations[midx].slice(0..1).toTypedArray()),
                cache.cachedLookup(::f2, mutations[midx][0], mutations[midx][1])!!["params"]
            )
        }
        assertEquals(5 + 4 + 3 + 2, cache.cache.estimatedSize())

        assertEquals(
            jsonArrayOf(*mutations[0].slice(0..0).toTypedArray()),
            cache.cachedLookup(::f1, mutations[0][0])!!["params"]
        )
        assertEquals(5 + 4 + 3 + 2 + 1, cache.cache.estimatedSize())

        /// Suspend variants:
        for (midx in 0..4) {
            assertEquals(jsonArrayOf(*mutations[midx].toTypedArray()),
                runBlocking {
                    cache.cachedLookup(
                        ::sf5,
                        mutations[midx][0],
                        mutations[midx][1],
                        mutations[midx][2],
                        mutations[midx][3],
                        mutations[midx][4]
                    )!!["params"]
                })
        }
        assertEquals(15 + 5, cache.cache.estimatedSize())

        for (midx in 0..3) {
            assertEquals(jsonArrayOf(*mutations[midx].slice(0..3).toTypedArray()),
                runBlocking {
                    cache.cachedLookup(
                        ::sf4,
                        mutations[midx][0],
                        mutations[midx][1],
                        mutations[midx][2],
                        mutations[midx][3]
                    )!!["params"]
                })
        }
        assertEquals(15 + 5 + 4, cache.cache.estimatedSize())

        for (midx in 0..2) {
            assertEquals(jsonArrayOf(*mutations[midx].slice(0..2).toTypedArray()),
                runBlocking {
                    cache.cachedLookup(
                        ::sf3,
                        mutations[midx][0],
                        mutations[midx][1],
                        mutations[midx][2]
                    )!!["params"]
                })
        }
        assertEquals(15 + 5 + 4 + 3, cache.cache.estimatedSize())

        for (midx in 0..1) {
            assertEquals(jsonArrayOf(*mutations[midx].slice(0..1).toTypedArray()),
                runBlocking { cache.cachedLookup(::sf2, mutations[midx][0], mutations[midx][1])!!["params"] })
        }
        assertEquals(15 + 5 + 4 + 3 + 2, cache.cache.estimatedSize())

        assertEquals(jsonArrayOf(*mutations[0].slice(0..0).toTypedArray()),
            runBlocking { cache.cachedLookup(::sf1, mutations[0][0])!!["params"] })
        assertEquals(15 + 5 + 4 + 3 + 2 + 1, cache.cache.estimatedSize())
    }

    private fun f5Nullable(p1: String?, p2: String?, p3: String?, p4: String?, p5: String?): JsonObject =
        buildJsonObject { put("params", buildJsonArray { add(p1); add(p2); add(p3); add(p4); add(p5) }) }

    @Test
    fun `should not crash on NULL-parameters`() {
        val cache = InMemoryLookupCache(
            serializer = JsonObject.serializer(),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )
        for (i in 1..10) {
            assertEquals(
                jsonArrayOf("1", null, "3", "4", "5"),
                cache.cachedLookup(::f5Nullable, "1", null, "3", "4", "5")!!["params"]
            )
        }
        assertEquals(1, cache.cache.estimatedSize())
    }

    @Test
    fun `cache-content should be stored as JWE`() {
        val cache = InMemoryLookupCache(
            serializer = ListSerializer(JsonObject.serializer()),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )
        "2222".let { fnr ->
            validate5Times(cache, fnr)
            assertEquals(1, fnrCount[fnr])
        }
        cache.cache.asMap().apply {
            assertEquals(1, this.size)
            this.entries.first().apply {
                val jwe = JWEObject.parse(this.value.serializedValue)
                assertEquals(JWEObject.State.ENCRYPTED, jwe.state)
            }
        }
    }

    @Test
    fun `answers should not be mixed up even when request-hash-collision`() {
        val cache = InMemoryLookupCache(
            serializer = ListSerializer(JsonObject.serializer()),
            collectorRegistry = CollectorRegistry.defaultRegistry
        )
        val answer2222 = cache.cachedLookup(::someLookupFunc, "2222", fom, tom)!!
        assertEquals(1, fnrCount["2222"])
        assertEquals(answer2222, cache.cachedLookup(::someLookupFunc, "2222", fom, tom)!!)
        assertEquals(
            1,
            fnrCount["2222"],
            "test-sanity-check: should still be only one _actual_ lookup since result is cached"
        )
        val answer3333 = cache.cachedLookup(::someLookupFunc, "3333", fom, tom)!!
        assertEquals(1, fnrCount["3333"])
        cache.cache.asMap().apply {
            val entries = this.entries.toList()
            val firstKeyWithSecondValue = entries[0].key to entries[1].value
            val secondKeyWithFirstValue = entries[1].key to entries[0].value
            // Switch entries to simulate hash-collision:
            firstKeyWithSecondValue.let { cache.cache.put(it.first, it.second) }
            secondKeyWithFirstValue.let { cache.cache.put(it.first, it.second) }
        }
        val answer2222_2ndTime = cache.cachedLookup(::someLookupFunc, "2222", fom, tom)
        assertNotEquals(answer3333, answer2222_2ndTime, "MUST not be the wrong value!")
        assertEquals(answer2222, answer2222_2ndTime, "cache-miss should cause new lookup giving the correct value")
        assertEquals(2, fnrCount["2222"], "now we should have done a total of 2 _actual_ lookups for '2222'")
    }


    suspend fun suspendBuildJsonObject(init: JsonObjectBuilder.() -> Unit): JsonObject {
        delay(1)
        return buildJsonObject(init)
    }


    private fun jsonArrayOf(vararg vals: String?): JsonArray =
        buildJsonArray { vals.forEach { add(it) } }

    private fun validate5Times(cache: InMemoryLookupCache<List<JsonObject>>, fnr: String) {
        for (i in 1..5) {
            val res: List<JsonObject> = cache.cachedLookup(::someLookupFunc, fnr, fom, tom)!!
            assertEquals(2, res.size)
            res.find { it["rec"]!!.jsonPrimitive.int == 2 }!!.apply {
                assertEquals(tom.toString(), this["date"]!!.jsonPrimitive.content)
                assertEquals(fnr, this["fnr"]!!.jsonPrimitive.content)
            }
            res.find { it["rec"]!!.jsonPrimitive.int == 1 }!!.apply {
                assertEquals(fom.toString(), this["date"]!!.jsonPrimitive.content)
                assertEquals(fnr, this["fnr"]!!.jsonPrimitive.content)
            }

        }
    }


}