package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKHolder
import no.nav.helse.crypto.JWKSetHolder

open class OppslagsApp(
    kafkaClientId: String,
    infotype: String,
    interessertI: List<Interesse>,
    ignoreIfNotPresent: List<Interesse> = interessertI.filter { it.type == Meldingstype.RiskNeed.name },
    oppslagstjeneste: (List<JsonObject>) -> JsonElement,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSetHolder? = null,
    encryptionJWK: JWKHolder? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
    additionalHealthCheck: (() -> Boolean)? = null,
    skipMessagesOlderThanSeconds: Long = -1,
    disableWebEndpoints: Boolean = false,
    sessionAggregationFieldName: String = SESSION_AGGREGATION_FIELD_NAME_DEFAULT,
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    skipEmitIfNotPresent = ignoreIfNotPresent,
    answerer = OppslagsProducer(infotype, oppslagstjeneste, decryptionJWKS, encryptionJWK)::lagSvar,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck,
    skipMessagesOlderThanSeconds = skipMessagesOlderThanSeconds,
    disableWebEndpoints = disableWebEndpoints,
    sessionAggregationFieldName = sessionAggregationFieldName,
)