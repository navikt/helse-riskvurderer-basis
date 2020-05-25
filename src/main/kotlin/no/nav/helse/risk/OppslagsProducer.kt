package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import no.nav.helse.crypto.decryptFromJWE
import no.nav.helse.crypto.encryptAsJWE
import org.slf4j.LoggerFactory

@Serializable
internal data class Oppslagsmelding(
    val type: String = typeOppslagsresultat,
    val infotype: String,
    val vedtaksperiodeId: String,
    val data: JsonElement
)

@Serializable
internal data class OppslagsmeldingKryptert(
    val type: String = typeOppslagsresultat,
    val infotype: String,
    val vedtaksperiodeId: String,
    val data: String
)

internal class OppslagsProducer(
    private val infotype: String,
    private val oppslagstjeneste: (List<JsonObject>) -> JsonElement,
    private val decryptionJWKS: JWKSet?,
    private val encryptionJWK: JWK?
) {

    private val json = Json(JsonConfiguration.Stable)
    private val log = LoggerFactory.getLogger(OppslagsProducer::class.java)
    private val secureLog = LoggerFactory.getLogger("sikkerLogg")

    fun lagSvar(meldinger: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            log.info("Gjør oppslag for vedtaksperiodeId=$vedtaksperiodeId basert på ${meldinger.size} melding(er)")
            val data = oppslagstjeneste(meldinger.map(::decryptIfEncrypted))
            if (encryptionJWK != null) {
                log.info("Returnerer kryptert oppslagsresultat for vedtaksperiodeId=$vedtaksperiodeId med infotype=$infotype")
                json.toJson(OppslagsmeldingKryptert.serializer(), OppslagsmeldingKryptert(
                    infotype = infotype,
                    vedtaksperiodeId = vedtaksperiodeId,
                    data = data.encryptAsJWE(encryptionJWK)
                )).jsonObject
            } else {
                log.info("Returnerer oppslagsresultat for vedtaksperiodeId=$vedtaksperiodeId med infotype=$infotype")
                json.toJson(Oppslagsmelding.serializer(), Oppslagsmelding(
                    infotype = infotype,
                    vedtaksperiodeId = vedtaksperiodeId,
                    data = data
                )).jsonObject
            }
        } catch (ex: Exception) {
            val msg = "Feil under oppslag for vedtaksperiodeId=${vedtaksperiodeId}: ${ex.javaClass.simpleName}"
            log.error("$msg. Se secureLog for detaljer.")
            secureLog.error(msg, ex)
            return null
        }
    }

    private fun decryptIfEncrypted(message: JsonObject): JsonObject {
        return try {
            if (decryptionJWKS != null
                && message.containsKey("data")
                && message["data"]?.contentOrNull != null
                && message["data"]!!.content.startsWith("ey")) {
                val decrypted = JsonElement.decryptFromJWE(message["data"]!!.content, decryptionJWKS)
                json {}.copy(message.content.toMutableMap().apply { this["data"] = decrypted })
            } else {
                message
            }
        } catch (exceptionBecauseDataElementIsNotAStringAndThusNotJWE: JsonException) {
            message
        }
    }

}