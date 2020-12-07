package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import no.nav.helse.crypto.JWKSetHolder

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>,
    val begrunnelserSomAleneKreverManuellBehandling: List<String>,
    val passerteSjekker: List<String>,
    val metadata: Map<String, String>
)

class VurderingBuilder {
    private var score: Int = 0
    private val begrunnelser = mutableListOf<String>()
    private val passerteSjekker = mutableListOf<String>()
    private val begrunnelserSomAleneKreverManuellBehandling = mutableListOf<String>()
    private val metadata = mutableMapOf<String, String>()
    fun begrunnelse(begrunnelse: String, scoreTillegg: Int) : VurderingBuilder {
        begrunnelser += begrunnelse
        score += scoreTillegg
        return this
    }

    fun begrunnelseSomKreverManuellBehandling(begrunnelse: String) : VurderingBuilder {
        begrunnelser += begrunnelse
        begrunnelserSomAleneKreverManuellBehandling += begrunnelse
        score += 10
        return this
    }

    fun passerteSjekk(beskrivelse: String) : VurderingBuilder {
        passerteSjekker += beskrivelse
        return this
    }

    fun leggVedMetadata(key: String, value: String) : VurderingBuilder {
        metadata[key] = value
        return this
    }

    fun build(vekt: Int): Vurdering {
        if (vekt > 10) throw IllegalArgumentException("Vekt kan ikke være over 10")
        return Vurdering(
            score = minOf(10, score),
            vekt = vekt,
            begrunnelser = begrunnelser,
            begrunnelserSomAleneKreverManuellBehandling = begrunnelserSomAleneKreverManuellBehandling,
            passerteSjekker = passerteSjekker,
            metadata = metadata
        )
    }
}

open class VurderingsApp(
    kafkaClientId: String,
    interessertI: List<Interesse>,
    ignoreIfNotPresent: List<Interesse> = emptyList(),
    vurderer: (List<JsonObject>) -> Vurdering,
    windowTimeInSeconds: Long = 5,
    decryptionJWKS: JWKSetHolder? = null,
    emitEarlyWhenAllInterestsPresent: Boolean = true,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
    additionalHealthCheck: (() -> Boolean)? = null
) : RiverApp(
    kafkaClientId = kafkaClientId,
    interessertI = interessertI,
    skipEmitIfNotPresent = ignoreIfNotPresent,
    answerer = VurderingProducer(
        infotype = kafkaClientId,
        vurderer = vurderer,
        decryptionJWKS = decryptionJWKS)::lagVurdering,
    windowTimeInSeconds = windowTimeInSeconds,
    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
    collectorRegistry = collectorRegistry,
    launchAlso = launchAlso,
    additionalHealthCheck = additionalHealthCheck
)