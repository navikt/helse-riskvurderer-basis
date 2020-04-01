package no.nav.helse.risk

import kotlinx.serialization.builtins.list
import kotlinx.serialization.json.*
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer

private val json = Json(JsonConfiguration.Stable)

class JsonObjectSerializer: Serializer<JsonObject?> {
   override fun serialize(topic: String?, data: JsonObject?): ByteArray =
      json.stringify(JsonObject.serializer(), data!!).toByteArray()
}

class JsonObjectDeserializer: Deserializer<JsonObject?> {
   override fun deserialize(topic: String?, data: ByteArray): JsonObject? =
      json.parse(JsonObject.serializer(), String(data))
}

class JsonObjectListSerializer: Serializer<List<JsonObject>> {
   override fun serialize(topic: String?, data: List<JsonObject>): ByteArray =
      json.stringify(JsonObject.serializer().list, data).toByteArray()
}

class JsonObjectListDeserializer: Deserializer<List<JsonObject>> {
   override fun deserialize(topic: String?, data: ByteArray): List<JsonObject> =
      json.parse(JsonObject.serializer().list, String(data))
}
