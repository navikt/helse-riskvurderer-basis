package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.helse.crypto.JWKSetHolder
import org.slf4j.LoggerFactory

private val json = JsonRisk

private val log = LoggerFactory.getLogger(VurderingProducer::class.java)
private val secureLog = Sanity.getSecureLogger()

internal class VurderingProducer(
    private val infotype: String,
    private val vurderer: (List<JsonObject>) -> Vurdering,
    private val decryptionJWKS: JWKSetHolder?
) {

    fun lagVurdering(answers: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            log.info("Lager vurdering for vedtaksperiodeId=$vedtaksperiodeId")
            val vurdering = vurderer(answers.map { it.decryptIfEncrypted(decryptionJWKS) })
            json.encodeToJsonElement(Vurderingsmelding.serializer(), Vurderingsmelding(
                infotype = infotype,
                vedtaksperiodeId = vedtaksperiodeId,
                sjekkresultater = vurdering.sjekkresultat,
                score = vurdering.score,
                vekt = vurdering.vekt,
                begrunnelser = vurdering.begrunnelser,
                begrunnelserSomAleneKreverManuellBehandling = vurdering.begrunnelserSomAleneKreverManuellBehandling,
                passerteSjekker = vurdering.passerteSjekker,
                metadata = vurdering.metadata
            )).jsonObject
        } catch (ex: Exception) {
            log.error(
                "feil under vurdering: {}, info.size()={} meldingsTyper={}, se secure log for detaljer",
                ex::class.java.name, answers.size, answers.map { it.meldingsType() }.toString()
            )
            secureLog.error("Feil under vurdering", ex)
            if (secureLog.isDebugEnabled) {
                secureLog.debug("Feil under vurdering med data: $answers")
            }
            null
        }
    }
}

private fun JsonObject.meldingsType() : String? {
    var type = this[typeKey]?.jsonPrimitive?.content
    if (type == Meldingstype.oppslagsresultat.name) {
        type += "/${this[infotypeKey]?.jsonPrimitive?.contentOrNull}"
    }
    return type
}