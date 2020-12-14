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
    val sessionId: WindowBufferEmitterSessionId<K>
    val values: List<V>
    fun delete()
}

internal class MySessionStore<K, V>(
    private val metrics: WindowBufferEmitterMetrics,
    private val sessionEarlyExpireCondition: ((List<V>) -> Boolean)?) {

    private val data: MutableMap<WindowBufferEmitterSessionId<K>, MutableMap<UUID, Sess<K,V>>> = mutableMapOf()

    val activeKeys: Int get() = data.size

    private inner class MyExpiredSession(val session: Sess<K,V>) : ExpiredSession<K, V> {
        override val sessionId = session.sessionId
        override val values: List<V>
            get() = session.recs

        override fun delete() {
            data[sessionId]?.let {
                it.remove(session.id)
                synchronized(it) {
                    if (it.isEmpty()) {
                        data.remove(sessionId)
                    }
                }
            }
        }
    }

    private class Sess<K,V>(timestamp: Long, val sessionId: WindowBufferEmitterSessionId<K>) {
        val id = UUID.randomUUID()
        val intialTimestamp: Long = timestamp
        var lastActiveTimestamp: Long = timestamp
        val recs: MutableList<V> = mutableListOf()
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

    private fun Sess<K,V>.hasExpiredBy(timestamp: Long) =
        (timestamp - this.lastActiveTimestamp > sessionGapMs) ||
            (timestamp - this.intialTimestamp > sessionMaxAgeMs) ||
            this.expiredEarlyByCondition

    private fun Sess<K,V>.addToSession(value: V, timestamp: Long) {
        this.recs += value
        if (sessionEarlyExpireCondition?.let { it(recs) } ?: false) {
            this.expiredEarlyByCondition = true
            val sessionLifetimeMS = timestamp - this.intialTimestamp
            metrics.emittedByConditionAfterMS(sessionLifetimeMS)
            metrics.emittedByConditionWhenTimeLeftMS(sessionGapMs - sessionLifetimeMS)
        }
        this.lastActiveTimestamp = timestamp
    }

    fun set(sessionId: WindowBufferEmitterSessionId<K>, value: V, timestamp: Long) {
        set(sessionId, value, timestamp, false)
    }

    fun setAndReturnSessionOnEarlyExpiry(sessionId: WindowBufferEmitterSessionId<K>, value: V, timestamp: Long): ExpiredSession<K, V>? {
        return set(sessionId, value, timestamp, true)
    }

    private fun set(sessionId: WindowBufferEmitterSessionId<K>, value: V, timestamp: Long, checkExpiry: Boolean): MyExpiredSession? {
        val sessions = data[sessionId] ?: mutableMapOf<UUID, Sess<K,V>>().apply { data[sessionId] = this }
        synchronized(sessions) {
            val activeSession = sessions.values.lastOrNull().let {
                if (it == null || it.hasExpiredBy(timestamp))
                    Sess<K,V>(timestamp, sessionId).also { newSession -> sessions[newSession.id] = newSession }
                else it
            }
            activeSession.addToSession(value, timestamp)
            if (activeSession.expiredEarlyByCondition && checkExpiry) {
                return MyExpiredSession(activeSession)
            }
        }
        return null
    }

    fun allExpiredSessions(currentTime: Long, ignoreEarlyExpiredSessions: Boolean = false): List<ExpiredSession<K, V>> =
        data.keys.flatMap { allExpiredSessionsForKey(it, currentTime, ignoreEarlyExpiredSessions) }

    fun allExpiredSessionsForKey(sessionId: WindowBufferEmitterSessionId<K>, currentTime: Long, ignoreEarlyExpiredSessions: Boolean): List<ExpiredSession<K, V>> =
        data[sessionId]?.values?.filter {
            it.hasExpiredBy(currentTime)
                && !(ignoreEarlyExpiredSessions && it.expiredEarlyByCondition)
        }?.map {
            MyExpiredSession(it)
        } ?: emptyList()

}


private val log = LoggerFactory.getLogger(WindowBufferEmitter::class.java)

class WindowBufferEmittable(
    val messages: List<JsonObject>,
    val kafkaKey: String
)

class WindowBufferEmitter(private val windowSizeInSeconds: Long,
                          private val aggregateAndEmit: (WindowBufferEmittable) -> Unit,
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
                    try {
                        aggregateAndEmit(WindowBufferEmittable(messages = it.values, kafkaKey = it.sessionId.kafkaKey))
                    } catch (ex:Exception) {
                        log.error("Error emitting messages for session=${it.sessionId}, removing, ignoring and continuing...!", ex)
                    }
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

    fun store(sessionKey: String, value: JsonObject, kafkaKey: String, timestamp: Long) {
        if (earlyExpiryEnabled) {
            val earlyExpiredSession = store.setAndReturnSessionOnEarlyExpiry(WindowBufferEmitterSessionId(sessionKey, kafkaKey), value, timestamp)
            if (earlyExpiredSession != null) {
                metrics.emittedSessionComplete()
                aggregateAndEmit(WindowBufferEmittable(messages = earlyExpiredSession.values, kafkaKey = earlyExpiredSession.sessionId.kafkaKey))
                earlyExpiredSession.delete()
            }
        } else {
            store.set(WindowBufferEmitterSessionId(sessionKey, kafkaKey), value, timestamp)
        }
    }
}


class WindowBufferEmitterSessionId<K>(
    val sessionKey: K,
    val kafkaKey: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WindowBufferEmitterSessionId<*>
        if (sessionKey != other.sessionKey) return false
        if (kafkaKey != other.kafkaKey) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sessionKey?.hashCode() ?: 0
        result = 31 * result + kafkaKey.hashCode()
        return result
    }

    override fun toString(): String {
        return "UniqueSessionId(sessionKey=$sessionKey, kafkaKey='$kafkaKey')"
    }
}