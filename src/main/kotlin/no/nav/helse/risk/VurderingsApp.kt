package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKSetHolder

data class Vurdering(
    @Deprecated("Bruk sjekkresultat i stedet") val score: Int,
    @Deprecated("Bruk sjekkresultat i stedet") val vekt: Int,
    @Deprecated("Bruk sjekkresultat i stedet") val begrunnelser: List<String>,
    @Deprecated("Bruk sjekkresultat i stedet") val begrunnelserSomAleneKreverManuellBehandling: List<String>,
    val sjekkresultat: List<Sjekkresultat>,
    @Deprecated("Bruk sjekkresultat(score=0) i stedet") val passerteSjekker: List<String>,
    val metadata: Map<String, String>
)

class VurderingBuilder {
    private val metadata = mutableMapOf<String, String>()
    private val sjekkresultater = mutableListOf<Sjekkresultat>()
    private var sjekkIdCounter = 0
    private fun nySjekkId(): String = (++sjekkIdCounter).toString()

    fun sjekkresultat(treff: Sjekkresultat): VurderingBuilder {
        sjekkresultater.add(treff)
        return this
    }

    fun nySjekk(
        vekt: Int,
        id: String = nySjekkId(),
        kategorier: List<String> = emptyList(),
        block: SjekkresultatBuilder.() -> Sjekkresultat
    ) {
        val builder = SjekkresultatBuilder(vekt, id, kategorier)
        sjekkresultat(block.invoke(builder))
    }

    fun passertSjekk(vekt: Int, tekst: String, id: String = nySjekkId()) = nySjekk(vekt, id) {
        passert(tekst)
    }

    fun ikkeAktuellSjekk(tekst: String, id: String = nySjekkId()) = nySjekk(0, id) {
        ikkeAktuell(tekst)
    }

    inner class SjekkresultatBuilder internal constructor(
        val vekt: Int,
        val id: String,
        val kategorier: List<String> = emptyList()
    ) {
        private var finalized = false
        fun passert(tekst: String) = resultat(tekst = tekst, score = 0, kreverManuellBehandling = false)
        fun ikkeAktuell(tekst: String) = passert(tekst).copy(vekt = 0)
        fun kreverManuellBehandling(tekst: String) = resultat(tekst = tekst, score = 10, kreverManuellBehandling = true)
        fun resultat(
            tekst: String,
            score: Int,
            kreverManuellBehandling: Boolean = false,
            ytterligereKategorier: List<String> = emptyList()
        ): Sjekkresultat {
            if (finalized) throw IllegalStateException("Resultat er allerede generert")
            return Sjekkresultat(
                id = id,
                begrunnelse = tekst,
                vekt = vekt,
                score = score,
                kreverManuellBehandling = kreverManuellBehandling,
                kategorier = (kategorier + ytterligereKategorier).toSet().toList()
            ).also { finalized = true }
        }
    }

    @Deprecated("Bruk nySjekk() eller sjekkresultat() i stedet")
    fun begrunnelse(begrunnelse: String, scoreTillegg: Int): VurderingBuilder =
        sjekkresultat(
            Sjekkresultat(
                id = nySjekkId(),
                begrunnelse = begrunnelse,
                score = scoreTillegg,
                vekt = 10,
                kreverManuellBehandling = false
            )
        )

    @Deprecated("Bruk nySjekk() eller sjekkresultat() i stedet")
    fun begrunnelseSomKreverManuellBehandling(begrunnelse: String): VurderingBuilder =
        sjekkresultat(
            Sjekkresultat(
                id = nySjekkId(),
                begrunnelse = begrunnelse,
                score = 10,
                vekt = 10,
                kreverManuellBehandling = true
            )
        )

    @Deprecated("Bruk nySjekk() eller sjekkresultat(score=0) i stedet")
    fun passerteSjekk(beskrivelse: String): VurderingBuilder {
        sjekkresultat(
            Sjekkresultat(
                id = nySjekkId(),
                begrunnelse = beskrivelse,
                score = 0,
                vekt = 10,
                kreverManuellBehandling = false
            )
        )
        return this
    }

    fun leggVedMetadata(key: String, value: String): VurderingBuilder {
        metadata[key] = value
        return this
    }

    private fun bakoverkompatibel_begrunnelser(): List<String> =
        sjekkresultater.filter { it.score > 0 }.map { it.begrunnelse }

    private fun bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(): List<String> =
        sjekkresultater.filter { it.kreverManuellBehandling }.map { it.begrunnelse }

    private fun bakoverkompatibel_score(): Int =
        sjekkresultater.map { it.score }.sum()

    private fun bakoverkompatibel_passerteSjekker(): List<String> =
        sjekkresultater.filter { it.score == 0 && it.vekt != 0 }.map { it.begrunnelse }


    fun build(vekt: Int = 10): Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = minOf(10, bakoverkompatibel_score()),
            vekt = vekt,
            begrunnelser = bakoverkompatibel_begrunnelser(),
            begrunnelserSomAleneKreverManuellBehandling = bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(),
            sjekkresultat = sjekkresultater,
            passerteSjekker = bakoverkompatibel_passerteSjekker(),
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