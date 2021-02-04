package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Interesse::class.java)
private val secureLog = Sanity.getSecureLogger()

data class Interesse internal constructor(
    val type: String,
    val infotype: String? = null,
    val iterasjonsMatcher: ((Int) -> Boolean)? = null,
    val ikkePaakrevdHvis: ((List<JsonObject>) -> Boolean)? = null
) {
    companion object {
        val riskNeed: Interesse = Interesse(type = Meldingstype.RiskNeed.name)
        fun riskNeed(iterasjon: Int) = Interesse(type = Meldingstype.RiskNeed.name, iterasjonsMatcher = {
            it == iterasjon
        })

        fun riskNeedMedMinimum(iterasjon: Int) = Interesse(type = Meldingstype.RiskNeed.name, iterasjonsMatcher = {
            it >= iterasjon
        })

        fun oppslagsresultat(infotype: String, ikkePaakrevdHvis: ((List<JsonObject>) -> Boolean)? = null) =
            Interesse(
                type = Meldingstype.oppslagsresultat.name,
                infotype = infotype,
                ikkePaakrevdHvis = ikkePaakrevdHvis
            )

        fun oppslagsresultat(oppslagstype: Oppslagtype<*>, ikkePaakrevdHvis: ((List<JsonObject>) -> Boolean)? = null) =
            oppslagsresultat(infotype = oppslagstype.infotype, ikkePaakrevdHvis = ikkePaakrevdHvis)

        fun vurdering(infotype: String, ikkePaakrevdHvis: ((List<JsonObject>) -> Boolean)? = null) =
            Interesse(type = Meldingstype.vurdering.name, infotype = infotype, ikkePaakrevdHvis = ikkePaakrevdHvis)
    }

    override fun toString(): String {
        return "Interesse(type='$type', infotype=$infotype)"
    }

}

fun List<Pair<String, String?>>.tilInteresser() =
    this.map {
        Interesse(it.first, it.second, null)
    }

private fun Int?.matcherIterasjon(interesse: Interesse): Boolean {
    if (interesse.iterasjonsMatcher == null) return true
    if (this == null) return false
    return interesse.iterasjonsMatcher.invoke(this)
}

internal fun JsonObject.erInteressant(interesser: List<Interesse>) : Boolean =
    interesser.find { this.tilfredsstillerInteresse(it) } != null

internal fun Interesse.tilfredsstillesAv(meldinger: List<JsonObject>) : Boolean {
    meldinger.forEach {
        if (it.tilfredsstillerInteresse(this)) return true
    }
    if (this.ikkePaakrevdHvis != null && meldinger.isNotEmpty()) {
        try {
            return this.ikkePaakrevdHvis.invoke(meldinger)
        } catch (ex: Exception) {
            val feilmelding = "Feil under sjekk av om interesse.ikkePaakrevdHvis"
            log.error("$feilmelding - Se secureLog for detaljer")
            secureLog.error("$feilmelding, interesse=$this, currentSessionContents=$meldinger", ex)
        }
    }
    return false
}

private fun JsonObject.tilfredsstillerInteresse(
    interesse: Interesse
): Boolean {
    if (interesse.type == this[typeKey]?.jsonPrimitive?.content &&
        (interesse.infotype == null || (interesse.infotype == this[infotypeKey]?.jsonPrimitive?.content)) &&
        (this[iterasjonKey]?.jsonPrimitive?.intOrNull.matcherIterasjon(interesse))
    ) {
        return true
    }
    return false
}