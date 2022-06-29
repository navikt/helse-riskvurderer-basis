package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKSetHolder
import java.util.*

data class Vurdering(
    @Deprecated("Bruk sjekkresultat i stedet") val score: Int,
    @Deprecated("Bruk sjekkresultat i stedet") val vekt: Int,
    val begrunnelser: List<String>, // NB: vil bli deprecated (Bruk sjekkresultater i stedet)
    val begrunnelserSomAleneKreverManuellBehandling: List<String>, // NB: vil bli deprecated (Bruk sjekkresultater i stedet)
    val sjekkresultat: List<Sjekkresultat>,
    val passerteSjekker: List<String>, // NB: vil bli deprecated (Bruk sjekkresultater i stedet)
    val metadata: Map<String, String>,
    val subsumsjoner: List<JsonObject>
)


class VurderingBuilder {
    private val metadata = mutableMapOf<String, String>()
    private val sjekkresultater = mutableListOf<Sjekkresultat>()
    private val subsumsjoner = mutableListOf<JsonObject>()
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
        sjekk: SjekkresultatBuilder.() -> Sjekkresultat
    ) : Sjekkresultat {
        val builder = SjekkresultatBuilder(vekt, id, kategorier)
        return sjekk.invoke(builder).let {
            sjekkresultat(it)
            it
        }
    }

    fun passertSjekk(vekt: Int, tekst: String, id: String = nySjekkId()) : Sjekkresultat = nySjekk(vekt, id) {
        passert(tekst)
    }

    fun ikkeAktuellSjekk(tekst: String, id: String = nySjekkId()) : Sjekkresultat = nySjekk(0, id) {
        ikkeAktuell(tekst)
    }

    inner class SjekkresultatBuilder internal constructor(
        val vekt: Int,
        val id: String,
        val kategorier: List<String> = emptyList()
    ) {
        private var finalized = false
        fun passert(tekst: String) = resultat(tekst = tekst, score = 0, kreverManuellBehandling = false)
        fun passert(tekst: TekstMedVariabler) = resultat(tekst = tekst, score = 0, kreverManuellBehandling = false)
        fun ikkeAktuell(tekst: String) = passert(tekst).copy(vekt = 0)
        fun kreverManuellBehandling(tekst: String) = resultat(tekst = tekst, score = 10, kreverManuellBehandling = true)

        inner class TekstMedVariabler(
            val tekst: String,
            val vars: List<Any>
        ) {
            override fun toString(): String = tekst
                .replace("%", "%%")
                .replace("{}", "%s")
                .format(*vars.toTypedArray())
        }

        fun tekst(tekst: String, vararg vars: Any) = TekstMedVariabler(tekst = tekst, vars = vars.toList())

        fun resultat(
            tekst: String,
            score: Int,
            kreverManuellBehandling: Boolean = false,
            ytterligereKategorier: List<String> = emptyList(),
            variabler: List<String> = emptyList(),
            grunnlag: SjekkresultatGrunnlag? = null,
        ): Sjekkresultat {
            if (finalized) throw IllegalStateException("Resultat er allerede generert")
            return Sjekkresultat(
                id = id,
                tekst = tekst,
                variabler = variabler,
                vekt = vekt,
                score = score,
                kreverManuellBehandling = kreverManuellBehandling,
                kategorier = (kategorier + ytterligereKategorier).toSet().toList(),
                grunnlag = grunnlag
            ).also { finalized = true }
        }

        fun resultat(
            tekst: TekstMedVariabler,
            score: Int,
            kreverManuellBehandling: Boolean = false,
            ytterligereKategorier: List<String> = emptyList(),
            grunnlag: SjekkresultatGrunnlag? = null,
        ): Sjekkresultat = resultat(
            tekst = tekst.toString(),
            variabler = tekst.vars.map { it.toString() },
            score = score,
            kreverManuellBehandling = kreverManuellBehandling,
            ytterligereKategorier = ytterligereKategorier,
            grunnlag = grunnlag)
    }

    fun leggVedMetadata(key: String, value: String): VurderingBuilder {
        metadata[key] = value
        return this
    }

    @Serializable
    private data class SubsumsjonHeaderFields(
            val id: String,
            val versjon: String,
            val eventName: String,
            val kilde: String,
    ) {
        init {
            try { requireNotNull(UUID.fromString(id)) } catch (ex: Exception) {
                throw IllegalArgumentException("Klarte ikke parse @id som UUID", ex)
            }
            require(eventName == "subsumsjon", { "@event_name må være 'subsumsjon'" })
        }
    }

    fun leggVedSubsumsjon(subsumsjon: JsonObject) {
        requireNotNull(JsonRisk.decodeFromJsonElement(SubsumsjonHeaderFields.serializer(), subsumsjon))
        subsumsjoner.add(subsumsjon)
    }

    private fun bakoverkompatibel_begrunnelser(): List<String> =
        sjekkresultater.filter { it.score > 0 }.map { it.tekst() }

    private fun bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(): List<String> =
        sjekkresultater.filter { it.kreverManuellBehandling }.map { it.tekst() }

    private fun bakoverkompatibel_score(): Int =
        minOf(10, sjekkresultater.map { it.score }.sum())

    private fun bakoverkompatibel_passerteSjekker(): List<String> =
        sjekkresultater.filter { it.score == 0 && it.vekt != 0 }.map { it.tekst() }


    fun build(vekt: Int = 10): Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = bakoverkompatibel_score(),
            vekt = vekt,
            begrunnelser = bakoverkompatibel_begrunnelser(),
            begrunnelserSomAleneKreverManuellBehandling = bakoverkompatibel_begrunnelserSomAleneKreverManuellBehandling(),
            sjekkresultat = sjekkresultater,
            passerteSjekker = bakoverkompatibel_passerteSjekker(),
            metadata = metadata,
            subsumsjoner = subsumsjoner
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
    disableWebEndpoints: Boolean = false,
    sessionAggregationFieldName: String = SESSION_AGGREGATION_FIELD_NAME_DEFAULT,
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
    disableWebEndpoints = disableWebEndpoints,
    sessionAggregationFieldName = sessionAggregationFieldName,
)