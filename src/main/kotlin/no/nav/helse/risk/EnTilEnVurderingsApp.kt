package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

class EnTilEnVurderingsApp(kafkaClientId: String,
                           interessertI: Interesse,
                           vurderer: (JsonObject) -> Vurdering,
                           decryptionJWKS: JWKSet? = null,
                           collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
                           launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
                           additionalHealthCheck: (() -> Boolean)? = null) : VurderingsApp(
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
    additionalHealthCheck = additionalHealthCheck
)