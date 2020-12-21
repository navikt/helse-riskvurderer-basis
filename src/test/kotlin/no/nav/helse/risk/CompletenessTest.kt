package no.nav.helse.risk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class CompletenessTest {

    @Test
    fun `isCompleteMessageSetAccordingToInterests should give right answer`() {
        val interesser = listOf(
            "RiskNeed" to null,
            "oppslagsresultat" to "orginfo",
            "oppslagsresultat" to "sensitiv1",
            "oppslagsresultat" to "sensitiv2"
        )

        val incomplete = listOf(
            buildJsonObject {
                put("type", "RiskNeed")
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "orginfo")
            }
        )

        assertFalse(isCompleteMessageSetAccordingToInterests(incomplete, interesser.tilInteresser()))

        val complete = listOf(
            buildJsonObject {
                put("type", "RiskNeed")
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "orginfo")
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "sensitiv1")
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "sensitiv2")
            }
        )

        assertTrue(isCompleteMessageSetAccordingToInterests(complete, interesser.tilInteresser()))
    }

}