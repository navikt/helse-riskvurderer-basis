package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject

class EnTilEnVurderingsApp(kafkaClientId: String,
                           interessertITypeInfotype: Pair<String, String?>,
                           vurderer: (JsonObject) -> Vurdering,
                           environment: Environment = Environment(kafkaClientId)) : VurderingsApp(
    kafkaClientId = kafkaClientId,
    interessertITypeInfotype = listOf((interessertITypeInfotype)),
    vurderer = { info ->
        require(info.size == 1)
        vurderer(info.first())
    },
    windowTimeInSeconds = 0,
    environment = environment
)