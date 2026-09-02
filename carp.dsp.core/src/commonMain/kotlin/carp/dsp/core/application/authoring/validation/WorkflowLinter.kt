package carp.dsp.core.application.authoring.validation

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.ExternalInputSource
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import dk.cachet.carp.analytics.domain.validation.ValidationErrorCode
import dk.cachet.carp.analytics.domain.validation.ValidationIssue
import dk.cachet.carp.analytics.domain.validation.ValidationResult
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import io.github.oshai.kotlinlogging.KotlinLogging

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
 * Returns a list of [ValidationIssue] with ERROR or WARNING severity levels.
 *
 * Usage:
 * ```kotlin
 * val issues = WorkflowLinter.lint(descriptor)
 * val errors = issues.filter { it.severity == ValidationSeverity.ERROR }
 * if (errors.isNotEmpty()) {
 *     println("Workflow has ${errors.size} errors:")
 *     errors.forEach { println("  - ${it.message}") }
 * }
 * ```
 */
object WorkflowLinter
{
    private val logger = KotlinLogging.logger {}
    private val SUPPORTED_ENV_KINDS = setOf("conda", "pixi", "python", "system")

    /**
     * Validates a workflow descriptor and returns all linting issues.
     *
     * @param descriptor The workflow to validate
     * @param config Optional configuration to customize linter behaviour
     * @return [ValidationResult] containing all issues found (or ValidationResult.OK if none)
     */
    fun lint(descriptor: WorkflowDescriptor, config: LinterConfiguration = LinterConfiguration()): ValidationResult
    {
        logger.info { "Linting workflow '${descriptor.metadata.name}'" }
        val issues = mutableListOf<ValidationIssue>()

        // Build step lookup for dependency/environment checks
        val stepIds = descriptor.steps.mapNotNull { it.id }.toSet()
        val envIds = descriptor.environments.keys.toSet()

        // Check 0: `uses:` references must be resolved before linting
        issues += checkStepsResolved(descriptor)

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

        // Check 9: Unattributed external boundary inputs
        issues += checkExternalProvenance(descriptor)

        // Optional checks (configurable)
        issues += checkNamingConventions(descriptor, config)
        issues += checkMissingMetadata(descriptor, config)
        issues += checkUnusedEnvironments(descriptor, config)
        issues += checkWorkflowLength(descriptor, config)

        if (issues.isEmpty()) logger.info { "Linting passed with no issues" }
        else logger.warn {
            "Linting found ${issues.size} issue(s): ${issues.count { it.severity == ValidationSeverity.ERROR }} " +
                    "error(s), ${issues.count { it.severity == ValidationSeverity.WARNING }} warning(s)"
        }

        return if (issues.isEmpty()) ValidationResult.OK else ValidationResult(issues)
    }

    // ── Check 0: Unresolved `uses:` References ────────────────────────────────
    //
    // Resolution runs before linting, so a reference reaching the linter means
    // the resolve stage was skipped - a hard error rather than under-validating
    // a half-specified step.

    private fun checkStepsResolved(descriptor: WorkflowDescriptor): List<ValidationIssue> =
        descriptor.steps.filterIsInstance<ReferencedStepDescriptor>().map { step ->
            ValidationIssue(
                severity = ValidationSeverity.ERROR,
                code = ValidationErrorCode.WORKFLOW_UNRESOLVED_STEP_REFERENCE,
                message = "Step '${step.id ?: step.uses}' is an unresolved 'uses:' reference to " +
                    "'${step.uses}'. Resolve the workflow against the step library before linting.",
                path = "steps[${step.id ?: step.uses}]",
                subjectId = step.id ?: step.uses
            )
        }

    // ── Check 1: Duplicate Step IDs ───────────────────────────────────────────

    private fun checkDuplicateStepIds(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()
        val stepIds = descriptor.steps.mapNotNull { it.id }
        val duplicates = stepIds.groupingBy { it }.eachCount().filter { it.value > 1 }

        duplicates.forEach { (dupId, count) ->
            issues.add(
                ValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    code = ValidationErrorCode.WORKFLOW_STEP_ID_DUPLICATE,
                    message = "Step ID '$dupId' is duplicated $count times in workflow. " +
                        "Ensure all step IDs are unique.",
                    path = "steps",
                    subjectId = dupId
                )
            )
        }

        return issues
    }

    // ── Check 2 & 3: Duplicate Input/Output Port IDs ──────────────────────────

    private fun checkDuplicatePortIds(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.steps.forEach { step ->
            // Check input port duplicates
            val inputIds = step.inputs.mapNotNull { it.id }
            val dupInputs = inputIds.groupingBy { it }.eachCount().filter { it.value > 1 }
            dupInputs.forEach { (dupId, count) ->
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = ValidationErrorCode.STEP_INPUT_PORT_DUPLICATE_ID,
                        message = "Input port ID '$dupId' is duplicated $count times in step '${step.id}'. " +
                            "Ensure input port IDs are unique within the step.",
                        path = "steps[${step.id}].inputs",
                        subjectId = step.id
                    )
                )
            }

            // Check output port duplicates (a referenced step has no inline outputs)
            val outputIds = (step as? DefinedStepDescriptor)?.outputs.orEmpty().mapNotNull { it.id }
            val dupOutputs = outputIds.groupingBy { it }.eachCount().filter { it.value > 1 }
            dupOutputs.forEach { (dupId, count) ->
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = ValidationErrorCode.STEP_OUTPUT_PORT_DUPLICATE_ID,
                        message = "Output port ID '$dupId' is duplicated $count times in step '${step.id}'. " +
                            "Ensure output port IDs are unique within the step.",
                        path = "steps[${step.id}].outputs",
                        subjectId = step.id
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
    ): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.steps.forEach { step ->
            // A referenced step (`uses:`) has no environmentId of its own - it is
            // resolved from the library step, so there is nothing to check here.
            if (step is DefinedStepDescriptor && step.environmentId !in envIds)
            {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = ValidationErrorCode.WORKFLOW_MISSING_ENVIRONMENT,
                        message = "Step '${step.id}' references non-existent environment " +
                            "'${step.environmentId}'. Add environment '${step.environmentId}' " +
                            "to the environments section.",
                        path = "steps[${step.id}].environmentId",
                        subjectId = step.id
                    )
                )
            }
        }

        return issues
    }

    // ── Check 5: Unknown Environment Kind ─────────────────────────────────────

    private fun checkEnvironmentKinds(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.environments.forEach { (envId, envDesc) ->
            if (envDesc.kind.lowercase() !in SUPPORTED_ENV_KINDS)
            {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.WARNING,
                        code = ValidationErrorCode.WORKFLOW_UNKNOWN_ENV_KIND,
                        message = "Environment kind '${envDesc.kind}' is not recognized. " +
                            "Supported kinds: ${SUPPORTED_ENV_KINDS.joinToString(", ")}.",
                        path = "environments.$envId.kind",
                        subjectId = envId
                    )
                )
            }
        }

        return issues
    }

    // ── Check 6: UUID Format Validation ───────────────────────────────────────

    private fun checkUuidFormats(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.steps.forEach { step ->
            // Check step ID format
            val stepId = step.id
            if (stepId != null && !isValidUuid(stepId))
            {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.WARNING,
                        code = ValidationErrorCode.INVALID_UUID_FORMAT,
                        message = "Step ID '${step.id}' is not a valid UUID. " +
                            "Use semantic IDs (e.g., 'step-1') or valid UUIDs.",
                        path = "steps[${step.id}].id",
                        subjectId = step.id
                    )
                )
            }

            // Check task ID format (a referenced step has no inline task).
            val taskId = (step as? DefinedStepDescriptor)?.task?.id
            if (taskId != null && !isValidUuid(taskId))
            {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.WARNING,
                        code = ValidationErrorCode.INVALID_UUID_FORMAT,
                        message = "Task ID '$taskId' is not a valid UUID. " +
                            "Task IDs can be semantic strings or UUIDs.",
                        path = "steps[${step.id}].task.id",
                        subjectId = step.id
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
    ): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.steps.forEach { step ->
            step.dependsOn.forEach { upstreamId ->
                if (upstreamId !in stepIds)
                {
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            code = ValidationErrorCode.WORKFLOW_DEP_REFERENCE_MISSING,
                            message = "Step '${step.id}' references non-existent upstream step '$upstreamId'. " +
                                "Check the spelling of the upstream step ID.",
                            path = "steps[${step.id}].dependsOn",
                            subjectId = step.id
                        )
                    )
                }
            }
        }

        return issues
    }

    // ── Check 8: Cycle in Step Dependencies ───────────────────────────────────

    private fun checkDependencyCycles(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        // Build adjacency list — only steps with IDs can participate in named dependency cycles
        val graph = mutableMapOf<String, Set<String>>()
        descriptor.steps.forEach { step ->
            val stepId = step.id
            if ( stepId != null ) graph[stepId] = step.dependsOn.toSet()
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
            val stepId = step.id
            if ( stepId != null && stepId !in visited )
            {
                if ( hasCycle( stepId ) )
                {
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            code = ValidationErrorCode.WORKFLOW_DEP_CYCLE_DETECTED,
                            message = "Circular dependency detected involving step '${step.id}'. " +
                                "Review the step dependencies to break the cycle.",
                            path = "steps[${step.id}].dependsOn",
                            subjectId = step.id
                        )
                    )
                }
            }
        }

        return issues
    }

    // ── Check 9: Unattributed External Boundary Inputs ────────────────────────

    /**
     * Warns for external data inputs that have no attribution.
     *
     * Two cases, both WARNING:
     * - an `external` source with neither `uri` nor `citation`
     * - an input with no `source` at all, which is treated as an empty external
     *
     * Inputs sourced from a step output, file, environment variable, or study
     * protocol carry their provenance in the source itself and are not checked here.
     */
    private fun checkExternalProvenance(descriptor: WorkflowDescriptor): List<ValidationIssue>
    {
        val issues = mutableListOf<ValidationIssue>()

        descriptor.steps.forEach { step ->
            step.inputs.forEachIndexed { index, port ->
                val portLabel = port.id ?: "inputs[$index]"
                when (val source = port.source)
                {
                    is ExternalInputSource ->
                        if (source.uri.isNullOrBlank() && source.citation.isNullOrBlank())
                        {
                            issues.add(
                                ValidationIssue(
                                    severity = ValidationSeverity.WARNING,
                                    code = ValidationErrorCode.EXTERNAL_DATA_UNATTRIBUTED,
                                    message = "External input '$portLabel' in step '${step.id}' has no uri " +
                                        "or citation. Attribute the dataset so the workflow's data " +
                                        "provenance is documented.",
                                    path = "steps[${step.id}].inputs[$portLabel].source",
                                    subjectId = step.id
                                )
                            )
                        }

                    null ->
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.WARNING,
                                code = ValidationErrorCode.EXTERNAL_DATA_UNATTRIBUTED,
                                message = "Input '$portLabel' in step '${step.id}' declares no source; " +
                                    "it is treated as unattributed external data. Declare a " +
                                    "'protocol' or 'external' source to document where the data " +
                                    "comes from.",
                                path = "steps[${step.id}].inputs[$portLabel]",
                                subjectId = step.id
                            )
                        )

                    else -> { /* provenance is the source */ }
                }
            }
        }

        return issues
    }

    // ── Config Rule: Check Naming Convention ──────────────────────────────────

    private fun checkNamingConventions(
        descriptor: WorkflowDescriptor,
        config: LinterConfiguration
    ): List<ValidationIssue> {
        if (!config.enforceNamingConventions) return emptyList()

        val issues = mutableListOf<ValidationIssue>()
        val namingPattern = Regex("^[a-z0-9_-]+$")

        // Check step IDs
        for ((index, step) in descriptor.steps.withIndex()) {
            val stepId = step.id
            if (stepId != null && !stepId.matches(namingPattern)) {
                issues += ValidationIssue(
                    severity = ValidationSeverity.WARNING,
                    code = ValidationErrorCode.NAMING_CONVENTION_VIOLATION,
                    message = "Step ID '${step.id}' does not follow naming convention. " +
                        "Use lowercase letters, numbers, dashes, or underscores.",
                    path = "steps[$index].id",
                    subjectId = step.id
                )
            }
        }

        // Check environment IDs
        for ((envId, _) in descriptor.environments) {
            if (!envId.matches(namingPattern)) {
                issues += ValidationIssue(
                    severity = ValidationSeverity.WARNING,
                    code = ValidationErrorCode.NAMING_CONVENTION_VIOLATION,
                    message = "Environment ID '$envId' does not follow naming convention. " +
                        "Use lowercase letters, numbers, dashes, or underscores.",
                    path = "environments['$envId']",
                    subjectId = envId
                )
            }
        }

        return issues
    }

    // ── Config Rule: required metadata is present  ─────────────────────────────────────────────────────────

    private fun checkMissingMetadata(
        descriptor: WorkflowDescriptor,
        config: LinterConfiguration
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check step descriptions
        if (config.requireStepDescriptions) {
            val severity = if (config.missingMetadataSeverity == "ERROR") {
                ValidationSeverity.ERROR
            } else {
                ValidationSeverity.WARNING
            }

            for ((index, step) in descriptor.steps.withIndex()) {
                if (step.metadata?.description.isNullOrBlank()) {
                    issues += ValidationIssue(
                        severity = severity,
                        code = ValidationErrorCode.MISSING_METADATA,
                        message = "Step '${step.id}' is missing a description.",
                        path = "steps[$index].metadata",
                        subjectId = step.id
                    )
                }
            }
        }

        return issues
    }

    // ── Config Rule: Unused Environment ──────────────────────────

    private fun checkUnusedEnvironments(
        descriptor: WorkflowDescriptor,
        config: LinterConfiguration
    ): List<ValidationIssue> {
        if (!config.checkForUnusedEnvironments) return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        // Find all used environment IDs (referenced steps contribute none)
        val usedEnvIds = descriptor.steps.mapNotNull { (it as? DefinedStepDescriptor)?.environmentId }.toSet()

        // Check for defined but unused
        for ((envId, _) in descriptor.environments) {
            if (envId !in usedEnvIds) {
                issues += ValidationIssue(
                    severity = ValidationSeverity.WARNING,
                    code = ValidationErrorCode.UNUSED_ENVIRONMENT,
                    message = "Environment '$envId' is defined but never used by any step.",
                    path = "environments['$envId']",
                    subjectId = envId
                )
            }
        }

        return issues
    }

    // ── Config Rule: Check workflow length ────────────────────────────────────

    /**
     * Check that workflow doesn't exceed recommended step count.
     */
    private fun checkWorkflowLength(
        descriptor: WorkflowDescriptor,
        config: LinterConfiguration
    ): List<ValidationIssue> {
        if (descriptor.steps.size <= config.maxStepsWarningThreshold) return emptyList()

        return listOf(
            ValidationIssue(
                severity = ValidationSeverity.WARNING,
                code = ValidationErrorCode.WORKFLOW_TOO_LONG,
                message = "Workflow has ${descriptor.steps.size} steps " +
                    "(exceeds recommended maximum of ${config.maxStepsWarningThreshold}). " +
                    "Consider breaking it into smaller, more focused workflows.",
                path = "steps",
                subjectId = null
            )
        )
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
