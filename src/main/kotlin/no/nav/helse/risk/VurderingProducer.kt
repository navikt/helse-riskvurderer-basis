package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.*
import no.nav.helse.crypto.decryptFromJWE
import org.slf4j.LoggerFactory


private val json = Json(JsonConfiguration.Stable)

internal class VurderingProducer(
    private val infotype: String,
    private val vurderer: (List<JsonObject>) -> Vurdering,
    private val decryptionJWKS: JWKSet?
) {

    companion object {
        private val log = LoggerFactory.getLogger(VurderingProducer::class.java)
    }

    fun lagVurdering(answers: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            log.info("Lager vurdering for vedtaksperiodeId=$vedtaksperiodeId")
            val vurdering = vurderer(answers.map(::decryptIfEncrypted))
            json.toJson(Vurderingsmelding.serializer(), Vurderingsmelding(
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

    private fun decryptIfEncrypted(message: JsonObject): JsonObject {
        return try {
            if (decryptionJWKS != null
                && message.containsKey(dataKey)
                && message[dataKey]?.contentOrNull != null
                && message[dataKey]!!.content.startsWith("ey")) {
                val decrypted = JsonElement.decryptFromJWE(message[dataKey]!!.content, decryptionJWKS)
                json {}.copy(message.content.toMutableMap().apply { this[dataKey] = decrypted } )
            } else {
                message
            }
        } catch (exceptionBecauseDataElementIsNotAStringAndThusNotJWE: JsonException) {
            message
        }
    }

}