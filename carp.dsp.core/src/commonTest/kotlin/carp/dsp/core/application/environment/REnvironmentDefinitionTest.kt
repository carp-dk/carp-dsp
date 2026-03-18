package carp.dsp.core.application.environment

import dk.cachet.carp.common.application.UUID
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class REnvironmentDefinitionTest {

    @Test
    fun createsREnvironmentDefinition() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "analysis-env",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2", "dplyr", "tidyr")
        )

        assertEquals("4.3.0", definition.rVersion)
        assertEquals(3, definition.rPackages.size)
    }

    @Test
    fun validatesValidRVersion() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val errors = definition.validate()

        assertTrue(errors.isEmpty())
    }

    @Test
    fun rejectsInvalidRVersion() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env",
            rVersion = "invalid-version",
            rPackages = listOf("ggplot2")
        )

        val errors = definition.validate()

        assertTrue(errors.any { it.contains("Invalid R version format") })
    }

    @Test
    fun rejectsBlankRVersion() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env",
            rVersion = "",
            rPackages = listOf("ggplot2")
        )

        val errors = definition.validate()

        assertTrue(errors.any { it.contains("R version cannot be blank") })
    }

    @Test
    fun acceptsRenvLockFile() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env",
            rVersion = "4.3.0",
            renvLockFile = "/path/to/renv.lock"
        )

        val errors = definition.validate()

        assertTrue(errors.isEmpty())
    }

    @Test
    fun rejectsMissingPackagesAndLockFile() {
        val definition = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env",
            rVersion = "4.3.0",
            rPackages = emptyList(),
            renvLockFile = null
        )

        val errors = definition.validate()

        assertTrue(errors.any { it.contains("renvLockFile or rPackages") })
    }
}
