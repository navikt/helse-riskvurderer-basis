package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import no.nav.helse.crypto.JWKSetHolder
import org.slf4j.LoggerFactory


private val json = JsonRisk

internal class VurderingProducer(
    private val infotype: String,
    private val vurderer: (List<JsonObject>) -> Vurdering,
    private val decryptionJWKS: JWKSetHolder?
) {

    companion object {
        private val log = LoggerFactory.getLogger(VurderingProducer::class.java)
    }

    fun lagVurdering(answers: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            log.info("Lager vurdering for vedtaksperiodeId=$vedtaksperiodeId")
            val vurdering = vurderer(answers.map { it.decryptIfEncrypted(decryptionJWKS) })
            json.encodeToJsonElement(Vurderingsmelding.serializer(), Vurderingsmelding(
                infotype = infotype,
                vedtaksperiodeId = vedtaksperiodeId,
                score = vurdering.score,
                vekt = vurdering.vekt,
                begrunnelser = vurdering.begrunnelser,
                begrunnelserSomAleneKreverManuellBehandling = vurdering.begrunnelserSomAleneKreverManuellBehandling,
                metadata = vurdering.metadata
            )).jsonObject
        } catch (ex: Exception) {
            log.error("Feil under vurdering", ex)
            null
        }
    }
}