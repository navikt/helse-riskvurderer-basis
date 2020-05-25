package no.nav.helse.risk

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class CompletenessTest {

    @Test
    fun `isCompleteMessageSetAccordingToInterests should give right answer` () {
        val interesser = listOf(
            "RiskNeed" to null,
            "oppslagsresultat" to "orginfo",
            "oppslagsresultat" to "sensitiv1",
            "oppslagsresultat" to "sensitiv2"
        )

        val incomplete = listOf(
            json {
                "type" to "RiskNeed"
            },
            json {
                "type" to "oppslagsresultat"
                "infotype" to "orginfo"
            }
        )

        assertFalse(isCompleteMessageSetAccordingToInterests(incomplete, interesser.tilInteresser()))

        val complete = listOf(
            json {
                "type" to "RiskNeed"
            },
            json {
                "type" to "oppslagsresultat"
                "infotype" to "orginfo"
            },
            json {
                "type" to "oppslagsresultat"
                "infotype" to "sensitiv1"
            },
            json {
                "type" to "oppslagsresultat"
                "infotype" to "sensitiv2"
            }
        )

        assertTrue(isCompleteMessageSetAccordingToInterests(complete, interesser.tilInteresser()))
    }

}