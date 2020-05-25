package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonObject

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

open class VurderingsApp(
    kafkaClientId: String,
    interessertI: List<Interesse>,
    vurderer: (List<JsonObject>) -> Vurdering,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSet? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    answerer = VurderingProducer(
        infotype = kafkaClientId,
        vurderer = vurderer,
        decryptionJWKS = decryptionJWKS)::lagVurdering,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry
)