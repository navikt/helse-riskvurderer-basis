package no.nav.helse.risk

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer

val JsonRisk = Json {
   encodeDefaults = true
   ignoreUnknownKeys = true
}
private val json = JsonRisk

/* 0.20.0 Json.Stable was:
encodeDefaults = true,
ignoreUnknownKeys = false,
isLenient = false,
serializeSpecialFloatingPointValues = false,
allowStructuredMapKeys = true,
prettyPrint = false,
unquotedPrint = false,
indent = defaultIndent,
useArrayPolymorphism = false,
classDiscriminator = defaultDiscriminator

1.0.1 JsonRisk is:
    @JvmField public val encodeDefaults: Boolean = false,
    @JvmField public val ignoreUnknownKeys: Boolean = false,
    @JvmField public val isLenient: Boolean = false,
    @JvmField public val allowStructuredMapKeys: Boolean = false,
    @JvmField public val prettyPrint: Boolean = false,
    @JvmField public val prettyPrintIndent: String = "    ",
    @JvmField public val coerceInputValues: Boolean = false,
    @JvmField public val useArrayPolymorphism: Boolean = false,
    @JvmField public val classDiscriminator: String = "type",
    @JvmField public val allowSpecialFloatingPointValues: Boolean = false,
    @JvmField public val serializersModule: SerializersModule = EmptySerializersModule

*/

class JsonObjectSerializer: Serializer<JsonObject?> {
   override fun serialize(topic: String?, data: JsonObject?): ByteArray =
      json.encodeToString(JsonObject.serializer(), data!!).toByteArray()
}

class JsonObjectDeserializer: Deserializer<JsonObject?> {
   override fun deserialize(topic: String?, data: ByteArray): JsonObject =
      json.decodeFromString(JsonObject.serializer(), String(data))
}

class JsonObjectListSerializer: Serializer<List<JsonObject>> {
   override fun serialize(topic: String?, data: List<JsonObject>): ByteArray =
      json.encodeToString(ListSerializer(JsonObject.serializer()), data).toByteArray()
}

class JsonObjectListDeserializer: Deserializer<List<JsonObject>> {
   override fun deserialize(topic: String?, data: ByteArray): List<JsonObject> =
      json.decodeFromString(ListSerializer(JsonObject.serializer()), String(data))
}
