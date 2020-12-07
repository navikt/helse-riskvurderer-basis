package no.nav.helse.crypto

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet

interface JWKHolder {
    fun jwk() : JWK
}

interface JWKSetHolder {
    fun jwkSet() : JWKSet
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