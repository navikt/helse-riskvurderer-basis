package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import no.nav.helse.crypto.JWKHolder
import no.nav.helse.crypto.JWKSetHolder
import no.nav.helse.crypto.encryptAsJWE
import org.slf4j.LoggerFactory

@Serializable
internal data class Oppslagsmelding(
    val type: String = Meldingstype.oppslagsresultat.name,
    val infotype: String,
    val vedtaksperiodeId: String,
    val data: JsonElement
)

@Serializable
internal data class OppslagsmeldingKryptert(
    val type: String = Meldingstype.oppslagsresultat.name,
    val infotype: String,
    val vedtaksperiodeId: String,
    val data: String
)

internal class OppslagsProducer(
    private val infotype: String,
    private val oppslagstjeneste: (List<JsonObject>) -> JsonElement,
    private val decryptionJWKS: JWKSetHolder?,
    private val encryptionJWK: JWKHolder?
) {

    private val json = JsonRisk
    private val log = LoggerFactory.getLogger(OppslagsProducer::class.java)
    private val secureLog = Sanity.getSecureLogger()

    fun lagSvar(meldinger: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            log.info("Gjør oppslag for vedtaksperiodeId=$vedtaksperiodeId basert på ${meldinger.size} melding(er)")
            val data = oppslagstjeneste(meldinger.map { it.decryptIfEncrypted(decryptionJWKS)} )
            if (encryptionJWK != null) {
                log.info("Returnerer kryptert oppslagsresultat for vedtaksperiodeId=$vedtaksperiodeId med infotype=$infotype")
                json.encodeToJsonElement(OppslagsmeldingKryptert.serializer(), OppslagsmeldingKryptert(
                    infotype = infotype,
                    vedtaksperiodeId = vedtaksperiodeId,
                    data = data.encryptAsJWE(encryptionJWK)
                )).jsonObject
            } else {
                log.info("Returnerer oppslagsresultat for vedtaksperiodeId=$vedtaksperiodeId med infotype=$infotype")
                json.encodeToJsonElement(Oppslagsmelding.serializer(), Oppslagsmelding(
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

}