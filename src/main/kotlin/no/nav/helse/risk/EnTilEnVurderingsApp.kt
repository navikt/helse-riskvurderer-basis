package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.JsonObject

class EnTilEnVurderingsApp(kafkaClientId: String,
                           interessertITypeInfotype: Pair<String, String?>,
                           vurderer: (JsonObject) -> Vurdering,
                           environment: Environment = Environment(kafkaClientId),
                           decryptionJWKS: JWKSet? = null) : VurderingsApp(
    kafkaClientId = kafkaClientId,
    interessertITypeInfotype = listOf((interessertITypeInfotype)),
    vurderer = { info ->
        require(info.size == 1)
        vurderer(info.first())
    },
    windowTimeInSeconds = 0,
    environment = environment,
    decryptionJWKS = decryptionJWKS
)