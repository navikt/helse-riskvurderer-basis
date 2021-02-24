package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKSetHolder

class EnTilEnVurderingsApp(kafkaClientId: String,
                           interessertI: Interesse,
                           vurderer: (JsonObject) -> Vurdering,
                           decryptionJWKS: JWKSetHolder? = null,
                           collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
                           launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
                           additionalHealthCheck: (() -> Boolean)? = null,
                           disableWebEndpoints: Boolean = false) : VurderingsApp(
    kafkaClientId = kafkaClientId,
    interessertI = listOf(interessertI),
    vurderer = { info ->
        require(info.size == 1)
        vurderer(info.first())
    },
    windowTimeInSeconds = 0,
    decryptionJWKS = decryptionJWKS,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck,
    disableWebEndpoints = disableWebEndpoints
)