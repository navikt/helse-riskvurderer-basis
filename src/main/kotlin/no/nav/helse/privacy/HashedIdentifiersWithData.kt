package no.nav.helse.privacy

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
data class HashedIdAndData<T>(
    val hashedId: String,
    val data: T
)

@Serializable
data class HashedIdentifiersWithData<T>(
    val algorithm: Algorithm,
    val iterationCount: Int,
    val saltHexified: String,
    val identifiersAndData: List<HashedIdAndData<T>>,
    val sharedHashedValue: String? = null,
) {
    companion object {
        enum class Algorithm {
            PBKDF2WithHmacSHA512
        }

        /**
         * Hash Idene i en liste med Ider og verdier, v.h.a. PBKDF2 med "sliding computational cost".
         * MERK at dette kun gjør det littegrann tyngre å finne tilbake til de faktiske Idene
         * og må derfor kun benyttes som en ekstra obfuskering i tillegg til andre beskyttelsesmekanismer.
         * @param idAndDataPairs id-strenger med tilknyttet data
         * @param baseIterationCount iteration-count (computational cost) hvis kun ett element hashes.
         * Dette vil bli delt på antall elementer for å ha relativt stabil kost.
         * @param antattAntallSammenlikninger hvis antall elementer er mindre enn antattAntallSammenlikninger
         * @param sharedValue eventuell felles verdi som både produsent og konsument kjenner til, og som konsumenten kan
         * bruke i en 'health-check' for å verifisere at hashemetodene er i synk v.h.a. metoden sharedValueIs().
         * vil iterationCount i stedet bli delt på antattAntallSammenlikninger, for at eventuell sammenlikning
         * i annen tjeneste ikke skal bli uforholdsmessig tung og tidkrevende.
         */
        fun <T> fromIdAndDataPairs(
            idAndDataPairs: List<Pair<String, T>>,
            baseIterationCount: Int = 50000,
            antattAntallSammenlikninger: Int = 5,
            sharedValue: String? = null,
        ): HashedIdentifiersWithData<T> {
            val algorithm = Algorithm.PBKDF2WithHmacSHA512
            val random = SecureRandom()
            val salt = ByteArray(16)
            random.nextBytes(salt)
            val iterationCount = maxOf(
                1,
                baseIterationCount / maxOf(idAndDataPairs.size, antattAntallSammenlikninger)
            )

            fun hashId(id:String) : String {
                val spec: KeySpec = PBEKeySpec(id.toCharArray(), salt, iterationCount, 128)
                val factory = SecretKeyFactory.getInstance(algorithm.toString())
                return factory.generateSecret(spec).encoded.toHexString()
            }

            val sharedHashedValue = sharedValue?.let { hashId(it) }

            if (idAndDataPairs.isEmpty()) {
                return HashedIdentifiersWithData(
                    algorithm = algorithm, iterationCount = iterationCount, saltHexified = salt.toHexString(),
                    identifiersAndData = emptyList(),
                    sharedHashedValue = sharedHashedValue
                )
            }

            return HashedIdentifiersWithData(
                algorithm = algorithm,
                iterationCount = iterationCount,
                saltHexified = salt.toHexString(),
                identifiersAndData = idAndDataPairs.map { (id, data) ->
                    HashedIdAndData(hashId(id), data)
                },
                sharedHashedValue = sharedHashedValue
            )
        }

        private fun ByteArray.toHexString() = this.joinToString("") { String.format("%02x", it) }
        private fun String.decodeHex(): ByteArray {
            check(length % 2 == 0) { "Must have an even length" }
            return chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }
    }

    private fun doHashId(id:String) : String {
        val spec: KeySpec = PBEKeySpec(id.toCharArray(), saltHexified.decodeHex(), iterationCount, 128)
        val factory = SecretKeyFactory.getInstance(algorithm.toString())
        return factory.generateSecret(spec).encoded.toHexString()
    }

    /**
     * Finn alle verdier hvor hashedId tilsvarer angitt klartekst id
     */
    fun findAllById(id: String): List<T> {
        if (identifiersAndData.isEmpty()) return emptyList()
        val idHash = doHashId(id)
        return identifiersAndData.filter { it.hashedId == idHash }.map { it.data }
    }

    /**
     * Sjekk om angitt 'sharedValue' er samme 'sharedValue' som ble brukt ved opprettelsen av HashedIdentifiersWithData-objektet.
     * Dersom sharedValue ved opprettelsen ble satt til null returnerer metoden uansett false.
     */
    fun sharedValueIs(sharedValue: String) : Boolean {
        if (sharedHashedValue == null) return false
        return doHashId(sharedValue) == sharedHashedValue
    }
}