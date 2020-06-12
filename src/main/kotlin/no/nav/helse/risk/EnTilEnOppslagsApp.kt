package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement

class EnTilEnOppslagsApp(kafkaClientId: String,
                         infotype: String = kafkaClientId,
                         oppslagstjeneste: (RiskNeed) -> JsonElement,
                         encryptionJWK: JWK? = null,
                         decryptionJWKS: JWKSet? = null,
                         collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
                         launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
                         additionalHealthCheck: (() -> Boolean)? = null) : OppslagsApp(
    kafkaClientId = kafkaClientId,
    infotype = infotype,
    interessertI = listOf(Interesse.riskNeed(1)),
    oppslagstjeneste = { meldinger ->
        require(meldinger.size == 1)
        oppslagstjeneste(meldinger.first().tilRiskNeed())
    },
    windowTimeInSeconds = 0,
    decryptionJWKS = decryptionJWKS,
    encryptionJWK = encryptionJWK,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck
)