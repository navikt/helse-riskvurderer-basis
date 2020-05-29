package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonObject

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>,
    val begrunnelserSomAleneKreverManuellBehandling: List<String>? = null
)

class VurderingBuilder() {
    private var score: Int = 0
    private val begrunnelser = mutableListOf<String>()
    private val begrunnelserSomAleneKreverManuellBehandling = mutableListOf<String>()
    fun begrunnelse(begrunnelse: String, scoreTillegg: Int) {
        begrunnelser += begrunnelse
        score += scoreTillegg
    }

    fun begrunnelseSomKreverManuellBehandling(begrunnelse: String) {
        begrunnelser += begrunnelse
        begrunnelserSomAleneKreverManuellBehandling += begrunnelse
        score += 10
    }

    fun build(vekt: Int) : Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = minOf(10, score),
            vekt = vekt,
            begrunnelser = begrunnelser,
            begrunnelserSomAleneKreverManuellBehandling =
            if (begrunnelserSomAleneKreverManuellBehandling.isEmpty())
                null
            else begrunnelserSomAleneKreverManuellBehandling)
    }
}

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