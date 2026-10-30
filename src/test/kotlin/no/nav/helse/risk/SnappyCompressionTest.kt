package no.nav.helse.risk

import org.apache.kafka.common.compress.Compression
import org.apache.kafka.common.record.internal.RecordBatch
import org.apache.kafka.common.utils.internals.BufferSupplier
import org.apache.kafka.common.utils.internals.ByteBufferOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class SnappyCompressionTest {

    @Test
    fun `Kafka Snappy compression round trip`() {
        val input = "Kafka Snappy compression".repeat(100).toByteArray()
        val compression = Compression.snappy().build()
        val buffer = ByteBufferOutputStream(input.size)

        compression.wrapForOutput(buffer, RecordBatch.CURRENT_MAGIC_VALUE).use {
            it.write(input)
        }

        buffer.buffer().flip()
        BufferSupplier.create().use { supplier ->
            compression.wrapForInput(buffer.buffer(), RecordBatch.CURRENT_MAGIC_VALUE, supplier).use {
                assertArrayEquals(input, it.readAllBytes())
            }
        }
    }
}
