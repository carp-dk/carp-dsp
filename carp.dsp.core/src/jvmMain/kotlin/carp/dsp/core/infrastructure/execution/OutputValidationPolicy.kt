package carp.dsp.core.infrastructure.execution

/**
 * Controls how [StepOutputValidator] reacts to output discrepancies after a step completes.
 *
 * @param warnOnMissingDeclaredOutputs Emit a warning [dk.cachet.carp.analytics.application.execution.ExecutionIssue] for each declared output file
 *   that does not exist after the command finishes.
 * @param warnOnUnexpectedOutputs Emit a warning [dk.cachet.carp.analytics.application.execution.ExecutionIssue] listing files found under the step's
 *   outputs directory that were not declared in the step's bindings.
 * @param strictOutputs When true, a missing declared output escalates from warning to a hard
 *   [dk.cachet.carp.analytics.application.execution.ExecutionStatus.FAILED] with [dk.cachet.carp.analytics.application.execution.FailureKind.OUTPUT_MISSING]. Unexpected outputs remain warnings.
 */
data class OutputValidationPolicy(
    val warnOnMissingDeclaredOutputs: Boolean = true,
    val warnOnUnexpectedOutputs: Boolean = true,
    val strictOutputs: Boolean = false
) {
    companion object {
        /** Permissive: warn on everything, never fail. */
        val DEFAULT = OutputValidationPolicy()

        /** Strict: missing declared output causes step failure. */
        val STRICT = OutputValidationPolicy(strictOutputs = true)

        /** Silent: no output validation at all. */
        val SILENT = OutputValidationPolicy(
            warnOnMissingDeclaredOutputs = false,
            warnOnUnexpectedOutputs = false
        )
    }
}

