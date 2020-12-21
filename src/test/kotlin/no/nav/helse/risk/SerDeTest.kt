package no.nav.helse.risk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SerDeTest {

   @Test
   fun `serialize single object`() {
      val serializer = JsonObjectSerializer()
      val actual = serializer.serialize("whatever", singleObject)
      assertArrayEquals(singleObjectSerialized, actual)
   }

   @Test
   fun `deserialize single object`() {
      val deserializer = JsonObjectDeserializer()
      val actual = deserializer.deserialize("whatever", singleObjectSerialized)
      assertEquals(singleObject, actual)
   }

   @Test
   fun `serialize list`() {
      val serializer = JsonObjectListSerializer()
      val actual = serializer.serialize("whatever", list)
      assertArrayEquals(listSerialized, actual)
   }

   @Test
   fun `deserialize list`() {
      val deserializer = JsonObjectListDeserializer()
      val actual = deserializer.deserialize("whatever", listSerialized)
      assertEquals(list, actual)
   }

   companion object {
      val singleObjectSerialized = SerDeTest::class.java.getResource("/single_object.json").readText().trim().toByteArray()
      val singleObject = buildJsonObject {
         put("prop1", "val1")
         put("prop2", 2)
      }

      val listSerialized = SerDeTest::class.java.getResource("/array.json").readText().trim().toByteArray()
      val list = listOf(
         buildJsonObject {
            put("prop1", "val1")
            put("prop2", 2)
         },
         buildJsonObject {
            put("prop3", "val3")
            put("prop4", 4)
         }
      )
   }

}
