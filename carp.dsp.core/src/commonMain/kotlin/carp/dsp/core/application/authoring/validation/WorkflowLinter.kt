package carp.dsp.core.application.authoring.validation

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Linter for validating [WorkflowDescriptor] workflows.
 *
 * Checks for:
 * - Duplicate step IDs, input port IDs, output port IDs
 * - Environment references (exist, valid kind)
 * - Step dependency cycles
 * - Upstream step references (exist)
 * - UUID format validation (when present)
 *
 * Returns a list of [LintIssue] with "ERROR" (blocking) or "WARNING" (non-blocking) levels.
 *
 * Usage:
 * ```kotlin
 * val issues = WorkflowLinter.lint(descriptor)
 * val errors = issues.filter { it.level == "ERROR" }
 * if (errors.isNotEmpty()) {
 *     println("Workflow has ${errors.size} errors:")
 *     errors.forEach { println("  - ${it.message}") }
 * }
 * ```
 */
object WorkflowLinter
{
    private val SUPPORTED_ENV_KINDS = setOf("conda", "pixi", "python", "system")

    /**
     * Validates a workflow descriptor and returns all linting issues.
     *
     * @param descriptor The workflow to validate
     * @return List of [LintIssue] (empty if no issues found)
     */
    fun lint(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        // Build step lookup for dependency/environment checks
        val stepIds = descriptor.steps.mapNotNull { it.id }.toSet()
        val envIds = descriptor.environments.keys.toSet()

        // Check 1: Duplicate step IDs
        issues += checkDuplicateStepIds(descriptor)

        // Check 2 & 3: Duplicate input/output port IDs within each step
        issues += checkDuplicatePortIds(descriptor)

        // Check 4: Step references non-existent environment
        issues += checkEnvironmentReferences(descriptor, envIds)

        // Check 5: Unknown environment kind
        issues += checkEnvironmentKinds(descriptor)

        // Check 6: UUID format validation for step IDs and task IDs
        issues += checkUuidFormats(descriptor)

        // Check 7: Step depends on non-existent upstream step
        issues += checkUpstreamStepReferences(descriptor, stepIds)

        // Check 8: Cycle in step dependencies
        issues += checkDependencyCycles(descriptor)

        return issues
    }

    // ── Check 1: Duplicate Step IDs ───────────────────────────────────────────

    private fun checkDuplicateStepIds(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()
        val stepIds = descriptor.steps.mapNotNull { it.id }
        val duplicates = stepIds.groupingBy { it }.eachCount().filter { it.value > 1 }

        duplicates.forEach { (dupId, count) ->
            issues.add(
                LintIssue.error(
                    message = "Step ID '$dupId' is duplicated $count times in workflow",
                    suggestion = "Ensure all step IDs are unique"
                )
            )
        }

        return issues
    }

    // ── Check 2 & 3: Duplicate Input/Output Port IDs ──────────────────────────

    private fun checkDuplicatePortIds(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        descriptor.steps.forEach { step ->
            // Check input port duplicates
            val inputIds = step.inputs.mapNotNull { it.id }
            val dupInputs = inputIds.groupingBy { it }.eachCount().filter { it.value > 1 }
            dupInputs.forEach { (dupId, count) ->
                issues.add(
                    LintIssue.error(
                        message = "Input port ID '$dupId' is duplicated $count times",
                        stepId = step.id,
                        fieldName = "inputs",
                        suggestion = "Ensure input port IDs are unique within the step"
                    )
                )
            }

            // Check output port duplicates
            val outputIds = step.outputs.mapNotNull { it.id }
            val dupOutputs = outputIds.groupingBy { it }.eachCount().filter { it.value > 1 }
            dupOutputs.forEach { (dupId, count) ->
                issues.add(
                    LintIssue.error(
                        message = "Output port ID '$dupId' is duplicated $count times",
                        stepId = step.id,
                        fieldName = "outputs",
                        suggestion = "Ensure output port IDs are unique within the step"
                    )
                )
            }
        }

        return issues
    }

    // ── Check 4: Step References Non-Existent Environment ────────────────────

    private fun checkEnvironmentReferences(
        descriptor: WorkflowDescriptor,
        envIds: Set<String>
    ): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        descriptor.steps.forEach { step ->
            if (step.environmentId !in envIds)
            {
                issues.add(
                    LintIssue.error(
                        message = "Environment '${step.environmentId}' not found in workflow",
                        stepId = step.id,
                        fieldName = "environmentId",
                        suggestion = "Add environment '${step.environmentId}' to the environments section"
                    )
                )
            }
        }

        return issues
    }

    // ── Check 5: Unknown Environment Kind ─────────────────────────────────────

    private fun checkEnvironmentKinds(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        descriptor.environments.forEach { (envId, envDesc) ->
            if (envDesc.kind.lowercase() !in SUPPORTED_ENV_KINDS)
            {
                issues.add(
                    LintIssue.warning(
                        message = "Environment kind '${envDesc.kind}' is not recognized",
                        fieldName = "environments.$envId.kind",
                        suggestion = "Supported kinds: ${SUPPORTED_ENV_KINDS.joinToString(", ")}"
                    )
                )
            }
        }

        return issues
    }

    // ── Check 6: UUID Format Validation ───────────────────────────────────────

    private fun checkUuidFormats(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        descriptor.steps.forEach { step ->
            // Check step ID format
            if (step.id != null && !isValidUuid(step.id))
            {
                issues.add(
                    LintIssue.warning(
                        message = "Step ID '${step.id}' is not a valid UUID",
                        stepId = step.id,
                        fieldName = "id",
                        suggestion = "Use semantic IDs (e.g., 'step-1') or valid UUIDs"
                    )
                )
            }

            // Check task ID format
            if (step.task.id != null && !isValidUuid(step.task.id!!))
            {
                issues.add(
                    LintIssue.warning(
                        message = "Task ID '${step.task.id}' is not a valid UUID",
                        stepId = step.id,
                        fieldName = "task.id",
                        suggestion = "Task IDs can be semantic strings or UUIDs"
                    )
                )
            }
        }

        return issues
    }

    // ── Check 7: Step Depends on Non-Existent Upstream Step ───────────────────

    private fun checkUpstreamStepReferences(
        descriptor: WorkflowDescriptor,
        stepIds: Set<String>
    ): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        descriptor.steps.forEach { step ->
            step.dependsOn.forEach { upstreamId ->
                if (upstreamId !in stepIds)
                {
                    issues.add(
                        LintIssue.error(
                            message = "Step references non-existent upstream step '$upstreamId'",
                            stepId = step.id,
                            fieldName = "dependsOn",
                            suggestion = "Check the spelling of the upstream step ID"
                        )
                    )
                }
            }
        }

        return issues
    }

    // ── Check 8: Cycle in Step Dependencies ───────────────────────────────────

    private fun checkDependencyCycles(descriptor: WorkflowDescriptor): List<LintIssue>
    {
        val issues = mutableListOf<LintIssue>()

        // Build adjacency list — only steps with IDs can participate in named dependency cycles
        val graph = mutableMapOf<String, Set<String>>()
        descriptor.steps.forEach { step ->
            if ( step.id != null ) graph[step.id] = step.dependsOn.toSet()
        }

        // Detect cycles using DFS
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun hasCycle( stepId: String ): Boolean
        {
            visited.add( stepId )
            recursionStack.add( stepId )

            graph[stepId]?.forEach { upstream ->
                when (upstream) {
                    !in visited -> if ( hasCycle( upstream ) ) return true
                    in recursionStack -> return true
                }
            }

            recursionStack.remove( stepId )
            return false
        }

        descriptor.steps.forEach { step ->
            if ( step.id != null && step.id !in visited )
            {
                if ( hasCycle( step.id ) )
                {
                    issues.add(
                        LintIssue.error(
                            message = "Circular dependency detected involving step '${step.id}'",
                            stepId = step.id,
                            fieldName = "dependsOn",
                            suggestion = "Review the step dependencies to break the cycle"
                        )
                    )
                }
            }
        }

        return issues
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Checks if a string is a valid UUID format.
     *
     * Valid UUIDs:
     * - 8-4-4-4-12 hex digits (standard format)
     * - With or without dashes
     * - Case-insensitive
     *
     * Invalid:
     * - Non-hex characters
     * - Semantic strings like "step-001" (intentionally invalid)
     */
    private fun isValidUuid(s: String): Boolean
    {
        // Standard UUID pattern: 8-4-4-4-12 hex digits
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        return s.matches(uuidPattern)
    }
}
