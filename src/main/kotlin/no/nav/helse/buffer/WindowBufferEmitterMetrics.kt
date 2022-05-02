package no.nav.helse.buffer

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Summary

class WindowBufferEmitterMetrics(collectorRegistry: CollectorRegistry,
        metricsNamePostfix: String? = null) {

    private fun validatedName(name: String): String {
        val maxLength = 10
        if (name.length > maxLength) throw IllegalArgumentException("metricsNamePostfix kan være maks $maxLength tegn")
        val okChars = 'a'..'z'
        name.forEach {
            if (!okChars.contains(it))
                throw IllegalArgumentException("metricsNamePostfix kan ikke inneholde $it")
        }
        return name
    }

    private val namePostfix: String = (metricsNamePostfix?.let { "_${validatedName(it)}" }) ?: ""

    private val emittedCounter = Counter
        .build("buffered_session_emitted$namePostfix", "antall forekomster av en aarsak til mulig risiko")
        .labelNames("state")
        .register(collectorRegistry)

    private val emittedByConditionAfterSecsSummary = Summary
        .build("buffered_session_emitted_after_secs_summary$namePostfix",
            "Alder på sesjon i sekunder når komplettert ved EarlyExpireCondition")
        .register(collectorRegistry)

    private val emittedByConditionWhenTimeLeftSecsSummary = Summary
        .build("buffered_session_emitted_time_left_secs_summary$namePostfix",
            "Tid igjen av maks sesjonsalder i sekunder når komplettert ved EarlyExpireCondition")
        .register(collectorRegistry)

    fun emittedSessionUnconditional() {
        emittedCounter.labels("unconditional").inc()
    }

    fun emittedSessionComplete() {
        emittedCounter.labels("complete").inc()
    }

    fun emittedSessionIncomplete() {
        emittedCounter.labels("incomplete").inc()
    }

    fun emittedByConditionAfterMS(ms: Long) {
        emittedByConditionAfterSecsSummary.observe(ms.toDouble() / 1000.0)
    }

    fun emittedByConditionWhenTimeLeftMS(ms: Long) {
        emittedByConditionWhenTimeLeftSecsSummary.observe(ms.toDouble() / 1000.0)
    }

}