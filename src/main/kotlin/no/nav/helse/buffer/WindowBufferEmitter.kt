package no.nav.helse.buffer

import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface ExpiredSession<K, V> {
    val key: K
    val values: List<V>
    fun delete()
}

internal class MySessionStore<K, V>(
    private val metrics: WindowBufferEmitterMetrics,
    private val sessionEarlyExpireCondition: ((List<V>) -> Boolean)?) {

    private val data: MutableMap<K, MutableMap<UUID, Sess<V>>> = mutableMapOf()

    val activeKeys: Int get() = data.size

    private inner class MyExpiredSession(override val key: K, val session: Sess<V>) : ExpiredSession<K, V> {
        override val values: List<V>
            get() = session.recs

        override fun delete() {
            data[key]?.let {
                it.remove(session.id)
                synchronized(it) {
                    if (it.isEmpty()) {
                        data.remove(key)
                    }
                }
            }
        }
    }

    private class Sess<T>(timestamp: Long) {
        val id = UUID.randomUUID()
        val intialTimestamp: Long = timestamp
        var lastActiveTimestamp: Long = timestamp
        val recs: MutableList<T> = mutableListOf()
        var expiredEarlyByCondition: Boolean = false
    }

    private var sessionMaxAgeVAR: Long = Duration.ofMinutes(30).toMillis()
    var sessionMaxAgeMs: Long
        get() = if (sessionMaxAgeVAR > sessionGapMs) sessionMaxAgeVAR else sessionGapMs
        set(value) {
            if (value < sessionGapMs) throw IllegalArgumentException("maxAgeMs cannot be smaller than sessionGapMs")
            sessionMaxAgeVAR = value
        }

    var sessionGapMs: Long = Duration.ofSeconds(5).toMillis()

    private fun Sess<V>.hasExpiredBy(timestamp: Long) =
        (timestamp - this.lastActiveTimestamp > sessionGapMs) ||
            (timestamp - this.intialTimestamp > sessionMaxAgeMs) ||
            this.expiredEarlyByCondition

    private fun Sess<V>.addToSession(value: V, timestamp: Long) {
        this.recs += value
        if (sessionEarlyExpireCondition?.let { it(recs) } ?: false) {
            this.expiredEarlyByCondition = true
            val sessionLifetimeMS = timestamp - this.intialTimestamp
            metrics.emittedByConditionAfterMS(sessionLifetimeMS)
            metrics.emittedByConditionWhenTimeLeftMS(sessionGapMs - sessionLifetimeMS)
        }
        this.lastActiveTimestamp = timestamp
    }

    fun set(key: K, value: V, timestamp: Long) {
        set(key, value, timestamp, false)
    }

    fun setAndReturnSessionOnEarlyExpiry(key: K, value: V, timestamp: Long): ExpiredSession<K, V>? {
        return set(key, value, timestamp, true)
    }

    private fun set(key: K, value: V, timestamp: Long, checkExpiry: Boolean): MyExpiredSession? {
        val sessions = data[key] ?: mutableMapOf<UUID, Sess<V>>().apply { data[key] = this }
        synchronized(sessions) {
            val activeSession = sessions.values.lastOrNull().let {
                if (it == null || it.hasExpiredBy(timestamp))
                    Sess<V>(timestamp).also { newSession -> sessions[newSession.id] = newSession }
                else it
            }
            activeSession.addToSession(value, timestamp)
            if (activeSession.expiredEarlyByCondition && checkExpiry) {
                return MyExpiredSession(key, activeSession)
            }
        }
        return null
    }

    fun allExpiredSessions(currentTime: Long, ignoreEarlyExpiredSessions: Boolean = false): List<ExpiredSession<K, V>> =
        data.keys.flatMap { allExpiredSessionsForKey(it, currentTime, ignoreEarlyExpiredSessions) }

    fun allExpiredSessionsForKey(key: K, currentTime: Long, ignoreEarlyExpiredSessions: Boolean): List<ExpiredSession<K, V>> =
        data[key]?.values?.filter {
            it.hasExpiredBy(currentTime)
                && !(ignoreEarlyExpiredSessions && it.expiredEarlyByCondition)
        }?.map {
            MyExpiredSession(key, it)
        } ?: emptyList()

}


private val log = LoggerFactory.getLogger(WindowBufferEmitter::class.java)

class WindowBufferEmitter(private val windowSizeInSeconds: Long,
                          private val aggregateAndEmit: (List<JsonObject>) -> Unit,
                          collectorRegistry: CollectorRegistry,
                          private val clock: Clock = Clock.systemDefaultZone(),
                          private val scheduleExpiryCheck: Boolean = true,
                          private val schedulerIntervalInSeconds: Long = windowSizeInSeconds,
                          sessionEarlyExpireCondition: ((List<JsonObject>) -> Boolean)? = null) {

    private val metrics = WindowBufferEmitterMetrics(collectorRegistry)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var runningExpiryCheck = false
    val earlyExpiryEnabled: Boolean = sessionEarlyExpireCondition != null
    val activeKeys: Int get() = store.activeKeys

    @Volatile private var lastExpiryCheckTimestamp:Long = System.currentTimeMillis()

    init {
        if (scheduleExpiryCheck) {
            scheduler.scheduleAtFixedRate({
                runExpiryCheck()
            }, schedulerIntervalInSeconds, schedulerIntervalInSeconds, TimeUnit.SECONDS)
        }
    }

    fun isHealty() : Boolean {
        if (scheduleExpiryCheck) {
            val now = System.currentTimeMillis()
            val msSinceLast = now - lastExpiryCheckTimestamp
            val msInterval = schedulerIntervalInSeconds * 1000
            if (msSinceLast > (msInterval * 10)) {
                log.error("lastExpiryCheckTimestamp = $lastExpiryCheckTimestamp/${Date(lastExpiryCheckTimestamp)}, now = $now/${Date(now)}, msSinceLast=$msSinceLast -> Unhealthy" )
                return false
            }
        }
        return true
    }

    internal fun runExpiryCheck() {
        try {
            if (!runningExpiryCheck) {
                lastExpiryCheckTimestamp = System.currentTimeMillis()
                runningExpiryCheck = true
                store.allExpiredSessions(
                    currentTime = clock.millis(),
                    // We don't want to emit result both here in scheduler and in "earlyEmitter":
                    ignoreEarlyExpiredSessions = earlyExpiryEnabled
                ).forEach {
                    if (earlyExpiryEnabled) {
                        metrics.emittedSessionIncomplete()
                    } else {
                        metrics.emittedSessionUnconditional()
                    }
                    aggregateAndEmit(it.values)
                    it.delete()
                }
            }
        } catch (ex:Exception) {
            log.error("Error during expiry-check", ex)
        } finally {
            runningExpiryCheck = false
        }
    }

    private val store = MySessionStore<String, JsonObject>(metrics, sessionEarlyExpireCondition).apply {
        sessionGapMs = windowSizeInSeconds * 1000
    }

    fun store(key: String, value: JsonObject, timestamp: Long) {
        if (earlyExpiryEnabled) {
            val earlyExpiredSession = store.setAndReturnSessionOnEarlyExpiry(key, value, timestamp)
            if (earlyExpiredSession != null) {
                metrics.emittedSessionComplete()
                aggregateAndEmit(earlyExpiredSession.values)
                earlyExpiredSession.delete()
            }
        } else {
            store.set(key, value, timestamp)
        }
    }
}
