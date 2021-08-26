package no.nav.helse.risk.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Summary
import kotlinx.serialization.KSerializer
import no.nav.helse.crypto.createRandomJWKAES
import no.nav.helse.crypto.decryptJWE
import no.nav.helse.crypto.encryptAsJWE
import no.nav.helse.crypto.jwkSecretKeyFrom
import no.nav.helse.risk.JsonRisk
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

private val log = LoggerFactory.getLogger(InMemoryLookupCache::class.java)

private val JSON = JsonRisk

class InMemoryLookupCache<RET>(
    private val serializer: KSerializer<RET>,
    collectorRegistry: CollectorRegistry,
    maximumSize: Long = 2000,
    expireAfterAccess: Duration = Duration.ofMinutes(60),
    private val oppslagstype: String = "main"
) {
    private val metrics = LookupCacheMetrics.getInstance(collectorRegistry)

    internal data class CachedValue(
        val timestamp: LocalDateTime = LocalDateTime.now(),
        val serializedValue: String
    )

    internal val cache: Cache<String, CachedValue> = Caffeine.newBuilder()
        .maximumSize(maximumSize)
        .expireAfterAccess(expireAfterAccess)
        .build()

    fun <P1> cachedLookup(function: (P1) -> RET?, param1: P1): RET? =
        requestParams(function, param1).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timed { function(param1) })
        }

    fun <P1, P2> cachedLookup(function: (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        requestParams(function, param1, param2).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timed { function(param1, param2) })
        }

    fun <P1, P2, P3> cachedLookup(function: (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        requestParams(function, param1, param2, param3).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timed { function(param1, param2, param3) })
        }

    fun <P1, P2, P3, P4> cachedLookup(function: (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        requestParams(function, param1, param2, param3, param4).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timed { function(param1, param2, param3, param4) })
        }

    fun <P1, P2, P3, P4, P5> cachedLookup(function: (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        requestParams(function, param1, param2, param3, param4, param5).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timed { function(param1, param2, param3, param4, param5) })
        }


    // SUSPEND-VERSIONS:
    suspend fun <P1> cachedLookup(function: suspend (P1) -> RET?, param1: P1): RET? =
        requestParams(function, param1).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timedSuspend { function(param1) })
        }

    suspend fun <P1, P2> cachedLookup(function: suspend (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        requestParams(function, param1, param2).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timedSuspend { function(param1, param2) })
        }

    suspend fun <P1, P2, P3> cachedLookup(function: suspend (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        requestParams(function, param1, param2, param3).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timedSuspend { function(param1, param2, param3) })
        }

    suspend fun <P1, P2, P3, P4> cachedLookup(function: suspend (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        requestParams(function, param1, param2, param3, param4).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timedSuspend { function(param1, param2, param3, param4) })
        }

    suspend fun <P1, P2, P3, P4, P5> cachedLookup(function: suspend (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        requestParams(function, param1, param2, param3, param4, param5).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: timedSuspend { function(param1, param2, param3, param4, param5) })
        }

    private suspend fun <T> timedSuspend(block: suspend () -> T) : T {
        val start = System.currentTimeMillis()
        return block().also {
            val millis = System.currentTimeMillis() - start
            metrics.millisSpentOnUncachedCall(millis)
        }
    }

    private fun <T> timed(block: () -> T) : T {
        val start = System.currentTimeMillis()
        return block().also {
            val millis = System.currentTimeMillis() - start
            metrics.millisSpentOnUncachedCall(millis)
        }
    }

    private fun requestParams(vararg params:Any?) : RequestInfo {
        return RequestInfo(params = params.map { "$it" })
    }

    private fun cacheIfNotNull(requestParams: RequestInfo, result: RET?) : RET? =
        result.also { if (result != null) cache.put(requestParams.requestHash(), CachedValue(serializedValue = serialize(result, requestParams))) }

    private fun cachedResult(requestParams: RequestInfo): RET? =
        try {
            val cached:CachedValue? = cache.getIfPresent(requestParams.requestHash())
            if (cached != null) {
                log.info("Using cached result ($oppslagstype) from ${cached.timestamp}")
                metrics.usedCache(oppslagstype)
                deserialize(cached.serializedValue, requestParams)
            } else {
                metrics.notInCache(oppslagstype)
                null
            }
        } catch (ex: Exception) {
            metrics.errorUsingCache(oppslagstype)
            log.error("Exception returning cached result ($oppslagstype): ${ex.javaClass}")
            null
        }

    private fun serialize(value: RET, requestParams: RequestInfo) : String {
        return encrypt(JSON.encodeToString(serializer, value), requestParams)
    }

    private fun deserialize(serializedValue: String, requestParams: RequestInfo) : RET {
        return JSON.decodeFromString(serializer, decrypt(serializedValue, requestParams))
    }

    private val masterKey:ByteArray = createRandomJWKAES().toOctetSequenceKey().toSecretKey("AES").encoded
    private val specificKeySalt:String = UUID.randomUUID().toString()
    private fun specificKey(requestParams: RequestInfo) : JWK {
        val saltedRequestHash = sha256Bytes(specificKeySalt + requestParams.params.joinToString("#"))
        val specificKey = masterKey.xor(saltedRequestHash)
        return jwkSecretKeyFrom(
            kid = "the-only-right-one",
            key = SecretKeySpec(specificKey, "AES"))
    }

    private fun decrypt(jwe: String, requestParams: RequestInfo): String {
        return decryptJWE(jwe, JWKSet(specificKey(requestParams)))
    }

    private fun encrypt(data: String, requestParams: RequestInfo): String {
        return encryptAsJWE(data.toByteArray(charset = Charsets.UTF_8), specificKey(requestParams))!!
    }

    private data class RequestInfo(val params: List<String>) {
        init { require(params.size >= 2) { "Should include at least function and one parameter" } }
        fun requestHash() : String = sha256(params.joinToString(";"))
    }

}

class LookupCacheMetrics private constructor(collectorRegistry: CollectorRegistry) {
    companion object {
        private val registryToInstance: MutableMap<CollectorRegistry, LookupCacheMetrics> = mutableMapOf()
        fun getInstance(collectorRegistry: CollectorRegistry) : LookupCacheMetrics {
            return registryToInstance.getOrPut(collectorRegistry) {
                LookupCacheMetrics(collectorRegistry)
            }
        }
    }
    private val cacheRequestCounter = Counter
        .build("risk_lookup_cache_counter", "antall forsoek på å hente ut fra cache")
        .labelNames("result", "oppslagstype")
        .register(collectorRegistry)

    private val milliesUsedForUncachedCall = Summary
        .build("risk_lookup_cache_uncached_millis_summary",
            "Millisekunder brukt for ikke-cachede kall")
        .register(collectorRegistry)

    fun usedCache(oppslagstype:String) { cacheRequestCounter.labels("used_cache", oppslagstype).inc() }
    fun notInCache(oppslagstype:String) { cacheRequestCounter.labels("not_in_cache", oppslagstype).inc() }
    fun errorUsingCache(oppslagstype:String) { cacheRequestCounter.labels("error", oppslagstype).inc() }

    fun millisSpentOnUncachedCall(ms: Long) {
        milliesUsedForUncachedCall.observe(ms.toDouble())
    }

}

private fun sha256Bytes(data:String): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data.toByteArray(charset = Charsets.UTF_8))
}

private fun sha256(data:String): String = sha256Bytes(data).toHexString()

private fun ByteArray.toHexString() = this.joinToString("") { String.format("%02x", it) }

// TODO: Test
internal fun ByteArray.xor(other: ByteArray): ByteArray {
    require(this.size == other.size)
    val ret = ByteArray(this.size)
    for (i in ret.indices) {
        ret[i] = this[i] xor other[i]
    }
    return ret
}