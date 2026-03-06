package carp.dsp.core.application.authoring.validation

/**
 * Configuration for customizing linter rule behaviour.
 *
 * Allows enabling/disabling specific checks and configuring thresholds
 * and severity levels for style and rules.
 */
data class LinterConfiguration(
    /**
     * Check that step and environment IDs follow naming conventions.
     * Pattern: lowercase-with-dashes or lowercase_with_underscores
     */
    val enforceNamingConventions: Boolean = true,

    /**
     * Check that all steps have descriptions.
     */
    val requireStepDescriptions: Boolean = false,

    /**
     * Check that all environments have descriptions.
     */
    val requireEnvironmentDescriptions: Boolean = false,

    /**
     * Check for unused environments (defined but not referenced by any step).

     */
    val checkForUnusedEnvironments: Boolean = true,

    /**
     * Check for workflows exceeding the step count threshold.
     */
    val maxStepsWarningThreshold: Int = 10,

    /**
     * Severity for missing metadata violations.
     */
    val missingMetadataSeverity: String = "WARNING",

    /**
     * Whether to enable all extended rules (convenience).
     * If true, enables all style/convention checks.
     * Default: false (conservative - only structural checks enabled by default)
     */
    val enableAllExtendedRules: Boolean = false,
)
{
    init {
        require(maxStepsWarningThreshold > 0) { "maxStepsWarningThreshold must be > 0" }
        require(missingMetadataSeverity in setOf("WARNING", "ERROR")) {
            "missingMetadataSeverity must be WARNING or ERROR"
        }
    }

    /**
     * Create a more strict configuration (enforces more rules).
     */
    companion object {
        /**
         * Strict configuration - enables all checks.
         * Useful for enforcing consistent code style.
         */
        val STRICT = LinterConfiguration(
            enforceNamingConventions = true,
            requireStepDescriptions = true,
            requireEnvironmentDescriptions = true,
            checkForUnusedEnvironments = true,
            maxStepsWarningThreshold = 10,
            missingMetadataSeverity = "ERROR",
            enableAllExtendedRules = true
        )

        /**
         * Lenient configuration - only structural checks.
         * Useful for rapid prototyping/development.
         */
        val LENIENT = LinterConfiguration(
            enforceNamingConventions = false,
            requireStepDescriptions = false,
            requireEnvironmentDescriptions = false,
            checkForUnusedEnvironments = false,
            maxStepsWarningThreshold = 100,
            missingMetadataSeverity = "WARNING",
            enableAllExtendedRules = false
        )

        /**
         * Default configuration - balanced.
         * Structural checks always on. Style checks on, but not strict.
         */
        val DEFAULT = LinterConfiguration()
    }
}
