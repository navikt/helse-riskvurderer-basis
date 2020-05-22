package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
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
                         environment: Environment = Environment(kafkaClientId),
                         encryptionJWK: JWK? = null,
                         decryptionJWKS: JWKSet? = null) : OppslagsApp(
    kafkaClientId = kafkaClientId,
    infotype = infotype,
    interessertITypeInfotype = listOf("RiskNeed" to null),
    oppslagstjeneste = { meldinger ->
        require(meldinger.size == 1)
        oppslagstjeneste(jsonFlexible.fromJson(RiskNeed.serializer(), meldinger.first()))
    },
    windowTimeInSeconds = 0,
    environment = environment,
    decryptionJWKS = decryptionJWKS,
    encryptionJWK = encryptionJWK
)