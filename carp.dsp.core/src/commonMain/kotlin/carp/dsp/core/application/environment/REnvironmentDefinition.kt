package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.Serializable

/**
 * Declarative author-time definition of an R execution environment.
 *
 * Contains only specification data (R version, packages, lock files).
 * Does not represent a materialized/runtime environment.
 */
@Serializable
data class REnvironmentDefinition(
    override val id: UUID,
    override val name: String,
    val rVersion: String,
    val rPackages: List<String> = emptyList(),
    val renvLockFile: String? = null,
    val installationPath: String? = null,
    override val dependencies: List<String> = emptyList(),
    override val environmentVariables: Map<String, String> = emptyMap()
) : EnvironmentDefinition {

    private companion object {
        private const val MIN_VERSION_SEGMENTS = 1
        private const val MAX_VERSION_SEGMENTS = 3
    }

    /**
     * Validate R environment specification.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (rVersion.isBlank()) {
            errors.add("R version cannot be blank")
        }

        // Validate version format
        if (!isValidRVersion(rVersion)) {
            errors.add("Invalid R version format: $rVersion")
        }

        return errors
    }

    /**
     * Check if version string is valid.
     * Expected format: MAJOR[.MINOR[.PATCH]] (e.g., "4", "4.3", "4.3.0")
     */
    private fun isValidRVersion(version: String): Boolean {
        val parts = version.split(".")
        if (parts.size !in MIN_VERSION_SEGMENTS..MAX_VERSION_SEGMENTS) return false
        return parts.all { it.toIntOrNull() != null }
    }
}
