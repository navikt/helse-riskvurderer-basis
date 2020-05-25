package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonElement

@Serializable
data class RiskNeed(
    val vedtaksperiodeId: String,
    val organisasjonsnummer: String,
    val fnr: String,
    val behovOpprettet: String,
    val iterasjon: Int,
    val type: String = "RiskNeed"
)
private val jsonFlexible = Json(JsonConfiguration.Stable.copy(
    ignoreUnknownKeys = true
))

class EnTilEnOppslagsApp(kafkaClientId: String,
                         infotype: String = kafkaClientId,
                         oppslagstjeneste: (RiskNeed) -> JsonElement,
                         encryptionJWK: JWK? = null,
                         decryptionJWKS: JWKSet? = null,
                         collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry) : OppslagsApp(
    kafkaClientId = kafkaClientId,
    infotype = infotype,
    interessertITypeInfotype = listOf("RiskNeed" to null),
    oppslagstjeneste = { meldinger ->
        require(meldinger.size == 1)
        oppslagstjeneste(jsonFlexible.fromJson(RiskNeed.serializer(), meldinger.first()))
    },
    windowTimeInSeconds = 0,
    decryptionJWKS = decryptionJWKS,
    encryptionJWK = encryptionJWK,
    collectorRegistry = collectorRegistry
)