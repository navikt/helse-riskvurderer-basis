package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import no.nav.helse.crypto.decryptFromJWE
import org.slf4j.LoggerFactory


private val json = Json(JsonConfiguration.Stable)

internal const val vedtaksperiodeIdKey = "vedtaksperiodeId"
internal const val typeKey = "type"
internal const val vurderingType = "vurdering"
internal const val infotypeKey = "infotype"

internal interface TopicAndClientIdHolder {
    val riskRiverTopic: String
    val kafkaClientId: String
}

@Serializable
internal data class Vurderingsmelding(
    val type: String = vurderingType,
    val infotype: String,
    val vedtaksperiodeId: String,
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

internal class VurderingProducer(
    private val topicConfig: TopicAndClientIdHolder,
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
                infotype = topicConfig.kafkaClientId,
                vedtaksperiodeId = vedtaksperiodeId,
                score = vurdering.score,
                vekt = vurdering.vekt,
                begrunnelser = vurdering.begrunnelser
            )).jsonObject
        } catch (ex: Exception) {
            log.error("Feil under vurdering", ex)
            null
        }
    }

    private fun decryptIfEncrypted(message: JsonObject): JsonObject {
        return try {
            if (decryptionJWKS != null
                && message.containsKey("data")
                && message["data"]?.contentOrNull != null
                && message["data"]!!.content.startsWith("ey")) {
                val decrypted = JsonElement.decryptFromJWE(message["data"]!!.content, decryptionJWKS)
                json {}.copy(message.content.toMutableMap().apply { this["data"] = decrypted } )
            } else {
                message
            }
        } catch (exceptionBecauseDataElementIsNotAStringAndThusNotJWE: JsonException) {
            message
        }
    }

}