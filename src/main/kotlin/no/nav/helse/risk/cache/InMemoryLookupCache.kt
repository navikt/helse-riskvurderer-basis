package no.nav.helse.risk.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
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
    expireAfterAccess: Duration = Duration.ofMinutes(60)
) {
    private val metrics = LookupCacheMetrics(collectorRegistry)

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
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1))
        }

    fun <P1, P2> cachedLookup(function: (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        requestParams(function, param1, param2).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2))
        }

    fun <P1, P2, P3> cachedLookup(function: (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        requestParams(function, param1, param2, param3).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3))
        }

    fun <P1, P2, P3, P4> cachedLookup(function: (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        requestParams(function, param1, param2, param3, param4).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3, param4))
        }

    fun <P1, P2, P3, P4, P5> cachedLookup(function: (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        requestParams(function, param1, param2, param3, param4, param5).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3, param4, param5))
        }


    // SUSPEND-VERSIONS:
    suspend fun <P1> cachedLookup(function: suspend (P1) -> RET?, param1: P1): RET? =
        requestParams(function, param1).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1))
        }

    suspend fun <P1, P2> cachedLookup(function: suspend (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        requestParams(function, param1, param2).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2))
        }

    suspend fun <P1, P2, P3> cachedLookup(function: suspend (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        requestParams(function, param1, param2, param3).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3))
        }

    suspend fun <P1, P2, P3, P4> cachedLookup(function: suspend (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        requestParams(function, param1, param2, param3, param4).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3, param4))
        }

    suspend fun <P1, P2, P3, P4, P5> cachedLookup(function: suspend (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        requestParams(function, param1, param2, param3, param4, param5).let { requestParams ->
            cacheIfNotNull(requestParams, cachedResult(requestParams) ?: function(param1, param2, param3, param4, param5))
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
                log.info("Using cached result from ${cached.timestamp}")
                metrics.usedCache()
                deserialize(cached.serializedValue, requestParams)
            } else {
                metrics.notInCache()
                null
            }
        } catch (ex: Exception) {
            metrics.errorUsingCache()
            log.error("Exception returning cached result: ${ex.javaClass}")
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

class LookupCacheMetrics(collectorRegistry: CollectorRegistry) {
    private val cacheRequestCounter = Counter
        .build("risk_lookup_cache_counter", "antall forsoek på å hente ut fra cache")
        .labelNames("result")
        .register(collectorRegistry)

    fun usedCache() { cacheRequestCounter.labels("used_cache").inc() }
    fun notInCache() { cacheRequestCounter.labels("not_in_cache").inc() }
    fun errorUsingCache() { cacheRequestCounter.labels("error").inc() }
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