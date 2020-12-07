package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val defaultSecretBasePath: Path = Paths.get("/var/run/secrets")
private val defaultVaultBasePath: Path = Paths.get("/var/run/secrets/nais.io/vault")

interface JWKHolder {
    fun jwk() : JWK
    companion object {
        fun fromDynamicFile(filepath: Path): JWKHolder = DynamicJWKFromPath(filepath)
        fun fromSecret(filename: String): JWKHolder = fromDynamicFile(defaultSecretBasePath.resolve(filename))
        fun fromVault(filename: String): JWKHolder = fromDynamicFile(defaultVaultBasePath.resolve(filename))
    }
}

interface JWKSetHolder {
    fun jwkSet() : JWKSet
    companion object {
        fun fromDynamicFile(filepath: Path): JWKSetHolder = DynamicJWKSetFromPath(filepath)
        fun fromSecret(filename: String): JWKSetHolder = fromDynamicFile(defaultSecretBasePath.resolve(filename))
        fun fromVault(filename: String): JWKSetHolder = fromDynamicFile(defaultVaultBasePath.resolve(filename))
        fun fromMultiple(vararg holders: JWKSetHolder) : JWKSetHolder = MultiJWKSetHolder(holders)

    }
}

internal class DynamicJWKFromPath(val filepath: Path) : JWKHolder {
    override fun jwk(): JWK = JWK.parse(Files.readString(filepath))
}
internal class DynamicJWKSetFromPath(val filepath: Path) : JWKSetHolder {
    override fun jwkSet(): JWKSet = JWKSet.parse(Files.readString(filepath))
}
internal class MultiJWKSetHolder(private val holders: Array<out JWKSetHolder>) : JWKSetHolder {
    override fun jwkSet(): JWKSet = JWKSet(holders.flatMap { it.jwkSet().keys })
}

fun JWK.toJWKHolder() : JWKHolder =
    object : JWKHolder {
        override fun jwk(): JWK {
            return this@toJWKHolder
        }
    }

fun JWKSet.toJWKSetHolder() : JWKSetHolder =
    object : JWKSetHolder {
        override fun jwkSet(): JWKSet {
            return this@toJWKSetHolder
        }
    }