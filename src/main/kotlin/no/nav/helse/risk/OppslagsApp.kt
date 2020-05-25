package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

open class OppslagsApp(
    kafkaClientId: String,
    infotype: String,
    interessertI: List<Interesse>,
    oppslagstjeneste: (List<JsonObject>) -> JsonElement,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSet? = null,
    encryptionJWK: JWK? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    answerer = OppslagsProducer(infotype, oppslagstjeneste, decryptionJWKS, encryptionJWK)::lagSvar,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry
)