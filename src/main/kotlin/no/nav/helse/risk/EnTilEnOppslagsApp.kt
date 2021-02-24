package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import no.nav.helse.crypto.JWKHolder
import no.nav.helse.crypto.JWKSetHolder

class EnTilEnOppslagsApp(kafkaClientId: String,
                         infotype: String = kafkaClientId,
                         oppslagstjeneste: (RiskNeed) -> JsonElement,
                         interesse: Interesse = Interesse.riskNeed,
                         encryptionJWK: JWKHolder? = null,
                         decryptionJWKS: JWKSetHolder? = null,
                         collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
                         launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
                         additionalHealthCheck: (() -> Boolean)? = null,
                         skipMessagesOlderThanSeconds: Long = -1,
                         disableWebEndpoints: Boolean = false) : OppslagsApp(
    kafkaClientId = kafkaClientId,
    infotype = infotype,
    interessertI = listOf(interesse),
    oppslagstjeneste = { meldinger ->
        require(meldinger.size == 1)
        oppslagstjeneste(meldinger.first().tilRiskNeed())
    },
    windowTimeInSeconds = 0,
    decryptionJWKS = decryptionJWKS,
    encryptionJWK = encryptionJWK,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck,
    skipMessagesOlderThanSeconds = skipMessagesOlderThanSeconds,
    disableWebEndpoints = disableWebEndpoints
)