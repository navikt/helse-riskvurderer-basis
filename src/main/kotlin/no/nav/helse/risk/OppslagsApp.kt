package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

open class OppslagsApp(
    kafkaClientId: String,
    infotype: String = kafkaClientId,
    interessertITypeInfotype: List<Pair<String, String?>>,
    oppslagstjeneste: (List<JsonObject>) -> JsonElement,
    windowTimeInSeconds: Long = 5,
    environment: Environment = Environment(kafkaClientId),
    decryptionJWKS: JWKSet? = null,
    encryptionJWK: JWK? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertITypeInfotype = interessertITypeInfotype,
    answerer = OppslagsProducer(infotype, oppslagstjeneste, decryptionJWKS, encryptionJWK)::lagSvar,
    windowTimeInSeconds = windowTimeInSeconds,
    environment = environment,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry
)