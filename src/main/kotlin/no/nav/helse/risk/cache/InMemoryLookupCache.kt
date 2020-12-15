package no.nav.helse.risk.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import kotlinx.serialization.Decoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonElement
import no.nav.helse.crypto.*
import no.nav.helse.crypto.decryptJWE
import org.slf4j.LoggerFactory
import java.lang.ClassCastException
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

private val log = LoggerFactory.getLogger(InMemoryLookupCache::class.java)

private val JSON = Json(JsonConfiguration.Stable)

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
        sha256("$function $param1").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1))
        }

    fun <P1, P2> cachedLookup(function: (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        sha256("$function $param1 $param2").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2))
        }

    fun <P1, P2, P3> cachedLookup(function: (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        sha256("$function $param1 $param2 $param3").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3))
        }

    fun <P1, P2, P3, P4> cachedLookup(function: (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        sha256("$function $param1 $param2 $param3 $param4").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3, param4))
        }

    fun <P1, P2, P3, P4, P5> cachedLookup(function: (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        sha256("$function $param1 $param2 $param3 $param4 $param5").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3, param4, param5))
        }


    // SUSPEND-VERSIONS:
    suspend fun <P1> cachedLookup(function: suspend (P1) -> RET?, param1: P1): RET? =
        sha256("$function $param1").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1))
        }

    suspend fun <P1, P2> cachedLookup(function: suspend (P1, P2) -> RET?, param1: P1, param2: P2): RET? =
        sha256("$function $param1 $param2").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2))
        }

    suspend fun <P1, P2, P3> cachedLookup(function: suspend (P1, P2, P3) -> RET?, param1: P1, param2: P2, param3: P3): RET? =
        sha256("$function $param1 $param2 $param3").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3))
        }

    suspend fun <P1, P2, P3, P4> cachedLookup(function: suspend (P1, P2, P3, P4) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4): RET? =
        sha256("$function $param1 $param2 $param3 $param4").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3, param4))
        }

    suspend fun <P1, P2, P3, P4, P5> cachedLookup(function: suspend (P1, P2, P3, P4, P5) -> RET?, param1: P1, param2: P2, param3: P3, param4: P4, param5: P5): RET? =
        sha256("$function $param1 $param2 $param3 $param4 $param5").let { requestHash ->
            cacheIfNotNull(requestHash, cachedResult(requestHash) ?: function(param1, param2, param3, param4, param5))
        }

    private fun cacheIfNotNull(requestHash: String, result: RET?) : RET? =
        result.also { if (result != null) cache.put(requestHash, CachedValue(serializedValue = serialize(result))) }

    private fun cachedResult(requestHash: String): RET? =
        try {
            val cached:CachedValue? = cache.getIfPresent(requestHash)
            if (cached != null) {
                log.info("Using cached result from ${cached.timestamp}");
                metrics.usedCache()
                deserialize(cached.serializedValue)
            } else {
                metrics.notInCache()
                null
            }
        } catch (ex: ClassCastException) {
            metrics.errorUsingCache()
            log.error("ClassCastException returning cached result")
            null
        }

    private fun serialize(value: RET) : String {
        return encrypt(JSON.stringify(serializer, value))
    }

    private fun deserialize(serializedValue: String) : RET {
        return JSON.parse(serializer, decrypt(serializedValue))
    }

    private val jwk = createRandomJWKAES()
    private val jwks = JWKSet(jwk)

    private fun decrypt(jwe: String): String {
        return decryptJWE(jwe, jwks)
    }

    private fun encrypt(data: String): String {
        return encryptAsJWE(data.toByteArray(charset = Charsets.UTF_8), jwk)!!
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

private fun sha256(data:String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(data.toByteArray(charset = Charsets.UTF_8))
    return hash.toHexString()
}

private fun ByteArray.toHexString() = this.joinToString("") { String.format("%02x", it) }