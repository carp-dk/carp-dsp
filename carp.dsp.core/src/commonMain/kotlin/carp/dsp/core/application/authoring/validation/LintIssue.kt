package carp.dsp.core.application.authoring.validation

/**
 * Represents a single linting issue found during workflow validation.
 *
 * Issues have an explicit severity level ("ERROR" or "WARNING") and a human-readable
 * message explaining the problem. Optional location information can help authors
 * pinpoint where in the workflow the issue occurs.
 *
 * @property level Severity: "ERROR" (blocking) or "WARNING" (non-blocking).
 * @property message Human-readable description of the issue.
 * @property stepId Optional: the ID of the step where the issue occurs.
 * @property fieldName Optional: the field name within the step (e.g., "task.id", "inputs[0].id").
 * @property suggestion Optional: suggested fix or clarification.
 */
data class LintIssue(
    val level: String,
    val message: String,
    val stepId: String? = null,
    val fieldName: String? = null,
    val suggestion: String? = null
)
{
    init
    {
        require(level in setOf("ERROR", "WARNING")) { "Level must be 'ERROR' or 'WARNING', got '$level'" }
    }

    /**
     * Human-readable string representation of this issue.
     *
     * Format: `ERROR message (in step-id.fieldName)`
     */
    override fun toString(): String
    {
        val location = when
        {
            stepId != null && fieldName != null -> " (in step '$stepId'.$fieldName)"
            stepId != null -> " (in step '$stepId')"
            fieldName != null -> " (in $fieldName)"
            else -> ""
        }
        return "[$level] $message$location".also { _ ->
            if (suggestion != null) println("  Suggestion: $suggestion")
        }
    }

    companion object
    {
        fun error(
            message: String,
            stepId: String? = null,
            fieldName: String? = null,
            suggestion: String? = null
        ) = LintIssue("ERROR", message, stepId, fieldName, suggestion)

        fun warning(
            message: String,
            stepId: String? = null,
            fieldName: String? = null,
            suggestion: String? = null
        ) = LintIssue("WARNING", message, stepId, fieldName, suggestion)
    }
}
