package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonObject

class EnTilEnVurderingsApp(kafkaClientId: String,
                           interessertITypeInfotype: Pair<String, String?>,
                           vurderer: (JsonObject) -> Vurdering,
                           decryptionJWKS: JWKSet? = null,
                           collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry) : VurderingsApp(
    kafkaClientId = kafkaClientId,
    interessertITypeInfotype = listOf((interessertITypeInfotype)),
    vurderer = { info ->
        require(info.size == 1)
        vurderer(info.first())
    },
    windowTimeInSeconds = 0,
    decryptionJWKS = decryptionJWKS,
    collectorRegistry = collectorRegistry
)