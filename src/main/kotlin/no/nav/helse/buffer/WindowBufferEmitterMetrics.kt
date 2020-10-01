package no.nav.helse.buffer

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Summary

class WindowBufferEmitterMetrics(private val collectorRegistry: CollectorRegistry) {

    private val emittedCounter = Counter
        .build("buffered_session_emitted", "antall forekomster av en aarsak til mulig risiko")
        .labelNames("state")
        .register(collectorRegistry)

    private val emittedByConditionAfterSecsSummary = Summary
        .build("buffered_session_emitted_after_secs_summary",
            "Alder på sesjon i sekunder når komplettert ved EarlyExpireCondition")
        .register(collectorRegistry)

    private val emittedByConditionWhenTimeLeftSecsSummary = Summary
        .build("buffered_session_emitted_time_left_secs_summary",
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