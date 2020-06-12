package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>,
    val begrunnelserSomAleneKreverManuellBehandling: List<String>? = null,
    val passerteSjekker: List<String>? = null // TODO: nullable inntil alle tjenester er migrert
)

class VurderingBuilder {
    private var score: Int = 0
    private val begrunnelser = mutableListOf<String>()
    private val passerteSjekker = mutableListOf<String>()
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

    fun passerteSjekk(beskrivelse: String) {
        passerteSjekker += beskrivelse
    }

    fun build(vekt: Int): Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = minOf(10, score),
            vekt = vekt,
            begrunnelser = begrunnelser,
            begrunnelserSomAleneKreverManuellBehandling =
            if (begrunnelserSomAleneKreverManuellBehandling.isEmpty())
                null
            else begrunnelserSomAleneKreverManuellBehandling,
            passerteSjekker = passerteSjekker)
    }
}

open class VurderingsApp(
    kafkaClientId: String,
    interessertI: List<Interesse>,
    vurderer: (List<JsonObject>) -> Vurdering,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSet? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
    additionalHealthCheck: (() -> Boolean)? = null
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    answerer = VurderingProducer(
        infotype = kafkaClientId,
        vurderer = vurderer,
        decryptionJWKS = decryptionJWKS)::lagVurdering,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck
)