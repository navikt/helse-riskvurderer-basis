package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.JsonObject

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

open class VurderingsApp(
    kafkaClientId: String,
    interessertITypeInfotype: List<Pair<String, String?>>,
    vurderer: (List<JsonObject>) -> Vurdering,
    windowTimeInSeconds: Long = 5,
    environment: Environment = Environment(kafkaClientId),
    decryptionJWKS: JWKSet? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertITypeInfotype = interessertITypeInfotype,
    answerer = VurderingProducer(environment, vurderer, decryptionJWKS)::lagVurdering,
    windowTimeInSeconds = windowTimeInSeconds,
    environment = environment,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent
)