package no.nav.helse.privacy

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom

class IdMasker(fieldNames: List<String> = listOf("hashedId", "sharedHashedValue", "fnr", "identifikator", "virksomhet", "opplysningspliktig"),
               primitiveFieldNamesExcludedFromMasking: Set<String> = emptySet()
) : Masker(
    replaceByKey = mapOf(
        *fieldNames.map { it to ::maskId }.toTypedArray()
    ),
    replaceStringValues = mapOf(
        ::looksLikeIdString to ::maskIdString
    ),
    fieldNameReplacers = mapOf(
        ::looksLikeIdString to ::maskIdString
    ),
    primitiveFieldNamesExcludedFromMasking = primitiveFieldNamesExcludedFromMasking
) {
    companion object {
        internal val hashingsalt = randomBytesAsString()
        private fun maskIdString(original: String) = sha1(hashingsalt + original).substring(0, original.length) + "(${original.length})"
        private fun maskId(original: JsonPrimitive) = JsonPrimitive(maskIdString(original.content))
        private fun isAllDigits(s: String):Boolean = s.find { !it.isDigit() } == null
        private fun looksLikeIdString(value: String) = isAllDigits(value)
    }
}

interface IMasker {
    fun mask(json: JsonElement) : JsonElement
}

open class Masker(
    val replaceByKey: Map<String, (JsonPrimitive) -> JsonPrimitive>,
    val replaceStringValues: Map<(String) -> Boolean, (String) -> String>,
    val fieldNameReplacers: Map<(String) -> Boolean, (String) -> String>,
    val primitiveFieldNamesExcludedFromMasking: Set<String> = emptySet()
) : IMasker {
    override fun mask(json: JsonElement) = maskElement(null, json)

    private fun maskElement(key: String?, value: JsonElement): JsonElement {
        if (value is JsonNull) return value
        if (value is JsonPrimitive) return maskPrimitive(key, value.jsonPrimitive)
        if (value is JsonObject) return maskObject(key, value.jsonObject)
        if (value is JsonArray) return maskArray(key, value.jsonArray)
        throw IllegalArgumentException("Unrecognized type: ${value.javaClass}")
    }

    private fun maskPrimitive(key: String?, originalValue: JsonPrimitive): JsonPrimitive {
        if (key != null && replaceByKey.containsKey(key)) {
            return replaceByKey[key]!!.invoke(originalValue)
        }
        return originalValue.let { primitive ->
            if (primitive.isString && !(primitiveFieldNamesExcludedFromMasking.contains(key))) {
                val stringMasker:((String) -> String)? = replaceStringValues.entries.find { it.key(primitive.content) }?.value
                if (stringMasker != null) {
                    return@let JsonPrimitive(stringMasker.invoke(primitive.content))
                }
            }
            primitive
        }
    }

    private fun maskObject(key: String?, value: JsonObject): JsonObject {
        return buildJsonObject {
            value.keys.forEach { fieldName ->
                val keyMasker:((String) -> String)? = fieldNameReplacers.entries.find { it.key(fieldName) }?.value
                val newName = keyMasker?.invoke(fieldName) ?: fieldName
                put(newName, maskElement(fieldName, value[fieldName]!!))
            }
        }
    }

    private fun maskArray(key: String?, value: JsonArray): JsonArray {
        return buildJsonArray {
            value.forEach {
                add(maskElement(null, it))
            }
        }
    }
}

internal fun randomBytesAsString(numBytes: Int = 20): String {
    val bytes = ByteArray(numBytes)
    SecureRandom().nextBytes(bytes)
    return bytes.toHexString()
}

internal fun sha1(data:String): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val hash = digest.digest(data.toByteArray(charset = Charsets.UTF_8))
    return hash.toHexString()
}
internal fun ByteArray.toHexString() = this.joinToString("") { String.format("%02x", it) }

