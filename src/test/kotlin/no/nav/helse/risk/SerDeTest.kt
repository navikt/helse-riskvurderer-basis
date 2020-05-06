package no.nav.helse.risk

import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

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
      val singleObject = json {
         "prop1" to "val1"
         "prop2" to 2
      }

      val listSerialized = SerDeTest::class.java.getResource("/array.json").readText().trim().toByteArray()
      val list = listOf(
         json {
            "prop1" to "val1"
            "prop2" to 2
         },
         json {
            "prop3" to "val3"
            "prop4" to 4
         }
      )
   }

}
