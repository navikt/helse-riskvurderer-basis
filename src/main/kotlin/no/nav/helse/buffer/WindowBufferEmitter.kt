package no.nav.helse.buffer

import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface ExpiredSession<K, V> {
   val key: K
   val values: List<V>
   fun delete()
}

class MySessionStore<K, V> {

   private val data: MutableMap<K, MutableMap<UUID, Sess<V>>> = mutableMapOf()

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
         (timestamp - this.intialTimestamp > sessionMaxAgeMs)


   private fun Sess<V>.addToSession(value: V, timestamp: Long) {
      this.recs += value
      this.lastActiveTimestamp = timestamp
   }

   fun set(key: K, value: V, timestamp: Long) {
      val sessions = data[key] ?: mutableMapOf<UUID, Sess<V>>().apply { data[key] = this }
      synchronized(sessions) {
         val activeSession = sessions.values.lastOrNull().let {
            if (it == null || it.hasExpiredBy(timestamp))
               Sess<V>(timestamp).also { newSession -> sessions[newSession.id] = newSession }
            else it
         }
         activeSession.addToSession(value, timestamp)
      }
   }

   fun allExpiredSessions(currentTime: Long): List<ExpiredSession<K, V>> =
      data.keys.flatMap { allExpiredSessionsForKey(it, currentTime) }

   fun allExpiredSessionsForKey(key: K, currentTime: Long): List<ExpiredSession<K, V>> =
      data[key]?.values?.filter { it.hasExpiredBy(currentTime) }?.map {
         MyExpiredSession(key, it)
      } ?: emptyList()

}

class WindowBufferEmitter(private val windowSizeInSeconds: Long,
                          private val aggregateAndEmit: (List<JsonObject>) -> Unit,
                          private val clock: Clock = Clock.systemDefaultZone(),
                          scheduleExpiryCheck: Boolean = true,
                          schedulerIntervalInSeconds:Long = windowSizeInSeconds) {

   private val scheduler = Executors.newSingleThreadScheduledExecutor()
   private var runningExpiryCheck = false

   init {
      if (scheduleExpiryCheck) {
         scheduler.scheduleAtFixedRate({
            runExpiryCheck()
         }, schedulerIntervalInSeconds, schedulerIntervalInSeconds, TimeUnit.SECONDS)
      }
   }

   internal fun runExpiryCheck() {
      try {
         if (!runningExpiryCheck) {
            runningExpiryCheck = true
            store.allExpiredSessions(clock.millis()).forEach {
               aggregateAndEmit(it.values)
               it.delete()
            }
         }
      } finally {
         runningExpiryCheck = false
      }
   }

   private val store = MySessionStore<String, JsonObject>().apply {
      sessionGapMs = windowSizeInSeconds * 1000
   }

   fun store(key: String, value: JsonObject, timestamp: Long) {
      store.set(key, value, timestamp)
   }
}
