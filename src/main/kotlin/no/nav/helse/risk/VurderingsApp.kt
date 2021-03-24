package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKSetHolder

data class Vurdering(
    @Deprecated("Bruk regeltreff i stedet") val score: Int,
    @Deprecated("Bruk regeltreff i stedet") val vekt: Int,
    @Deprecated("Bruk regeltreff i stedet") val begrunnelser: List<String>,
    @Deprecated("Bruk regeltreff i stedet") val begrunnelserSomAleneKreverManuellBehandling: List<String>,
    val regeltreff: List<Regeltreff>,
    val passerteSjekker: List<String>,
    val metadata: Map<String, String>
)

class VurderingBuilder {
    private val passerteSjekker = mutableListOf<String>()
    private val metadata = mutableMapOf<String, String>()
    private val regeltreff = mutableListOf<Regeltreff>()

    fun regeltreff(treff: Regeltreff): VurderingBuilder {
        regeltreff.add(treff)
        return this
    }

    @Deprecated("Bruk regeltreff i stedet")
    fun begrunnelse(begrunnelse: String, scoreTillegg: Int): VurderingBuilder =
        regeltreff(
            Regeltreff(
                begrunnelse = begrunnelse,
                score = scoreTillegg,
                vekt = 10
            )
        )

    @Deprecated("Bruk regeltreff i stedet")
    fun begrunnelseSomKreverManuellBehandling(begrunnelse: String): VurderingBuilder =
        regeltreff(
            Regeltreff(
                begrunnelse = begrunnelse,
                score = 10,
                vekt = 10,
                kreverManuellBehandling = true
            )
        )

    fun passerteSjekk(beskrivelse: String): VurderingBuilder {
        passerteSjekker += beskrivelse
        return this
    }

    fun leggVedMetadata(key: String, value: String): VurderingBuilder {
        metadata[key] = value
        return this
    }

    private fun bakoverkompatibel_begrunnelser(): List<String> =
        regeltreff.map { it.begrunnelse }

    private fun bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(): List<String> =
        regeltreff.filter { it.kreverManuellBehandling }.map { it.begrunnelse }

    private fun bakoverkompatibel_score(): Int =
        regeltreff.map { it.score }.sum()



    fun build(vekt: Int = 10): Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = minOf(10, bakoverkompatibel_score()),
            vekt = vekt,
            begrunnelser = bakoverkompatibel_begrunnelser(),
            begrunnelserSomAleneKreverManuellBehandling = bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(),
            regeltreff = regeltreff,
            passerteSjekker = passerteSjekker,
            metadata = metadata
        )
    }
}

open class VurderingsApp(
    kafkaClientId: String,
    interessertI: List<Interesse>,
    ignoreIfNotPresent: List<Interesse> = emptyList(),
    vurderer: (List<JsonObject>) -> Vurdering,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSetHolder? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
    additionalHealthCheck: (() -> Boolean)? = null,
    disableWebEndpoints: Boolean = false
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    skipEmitIfNotPresent = ignoreIfNotPresent,
    answerer = VurderingProducer(
        infotype = kafkaClientId,
        vurderer = vurderer,
        decryptionJWKS = decryptionJWKS
    )::lagVurdering,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck,
    disableWebEndpoints = disableWebEndpoints
)