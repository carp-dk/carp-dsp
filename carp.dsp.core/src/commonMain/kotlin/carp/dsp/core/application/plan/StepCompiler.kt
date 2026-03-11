package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.plan.TasksRun
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Module
import dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Script
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * Compiles a domain Step (definition-time model) into a PlannedStep containing:
 * - ResolvedBindings
 * - A concrete TasksRun subtype (e.g., CommandSpec)
 * - Collected PlanIssues (via passed issues list)
 *
 * Pure compiler - no IO, no filesystem, no environment materialization.
 * Emits issues instead of throwing exceptions.
 */
class StepCompiler {

    private val argTokenExpander = ArgTokenExpander()

    /**
     * Compiles a Step into a PlannedStep ready for execution.
     *
     * @param step The domain step to compile
     * @param bindings The resolved bindings for this step
     * @param issues Mutable list to collect any compilation issues
     * @return PlannedStep if compilation succeeds, null if fatal planning errors occur
     */
    fun compile(
        step: Step,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>
    ): PlannedStep? {
        val stepId = step.metadata.id

        // Validate step configuration
        if (!validateStep(step, issues)) {
            return null
        }

        // Compile task to TasksRun
        val tasksRun = compileTask(step.task, bindings, stepId, issues) ?: return null

        // Create PlannedStep
        return PlannedStep(
            stepId = stepId,
            name = step.metadata.name,
            process = tasksRun,
            bindings = bindings,
            environmentRef = step.environmentId
        )
    }

    /**
     * Compiles TaskDefinition into concrete TasksRun.
     */
    private fun compileTask(
        task: TaskDefinition,
        bindings: ResolvedBindings,
        stepId: UUID,
        issues: MutableList<PlanIssue>
    ): TasksRun? {
        return when (task) {
            is CommandTaskDefinition -> compileCommand(task, bindings, stepId, issues)
            is PythonTaskDefinition -> compilePython(task, bindings, stepId, issues)
            else -> {
                issues.add(
                    PlanIssue(
                        severity = PlanIssueSeverity.ERROR,
                        code = "UNSUPPORTED_TASK_TYPE",
                        message = "Task type '${task::class.simpleName}' is not supported. " +
                                "Only CommandTaskDefinition and PythonTaskDefinition are supported.",
                        stepId = stepId
                    )
                )
                null
            }
        }
    }

    /**
     * Handles CommandTaskDefinition compilation.
     * Expands tokens via ArgTokenExpander and builds CommandSpec.
     */
    private fun compileCommand(
        task: CommandTaskDefinition,
        bindings: ResolvedBindings,
        stepId: UUID,
        issues: MutableList<PlanIssue>
    ): CommandSpec {
        // Expand ArgTokens to concrete arguments
        val expandedArgs = argTokenExpander.expand(task.args, bindings, issues, stepId)

        return CommandSpec(
            executable = task.executable,
            args = expandedArgs
        )
    }

    private fun compilePython(
        task: PythonTaskDefinition,
        bindings: ResolvedBindings,
        stepId: UUID,
        issues: MutableList<PlanIssue>
    ): CommandSpec {
        // Convert entry point to ExpandedArg.Literal objects
        val entryPointArgs = when (val ep = task.entryPoint) {
            is Script -> {
                // Script: just the script path
                listOf(ExpandedArg.Literal(ep.scriptPath))
            }
            is Module -> {
                // Module: -m flag + module name
                listOf(
                    ExpandedArg.Literal("-m"),
                    ExpandedArg.Literal(ep.moduleName)
                )
            }
        }

        // Expand user-supplied ArgTokens
        val expandedArgs = argTokenExpander.expand(task.args, bindings, issues, stepId)

        return CommandSpec(
            executable = "python",
            args = entryPointArgs + expandedArgs
        )
    }

    /**
     * Validates step configuration and emits issues for problems.
     */
    private fun validateStep(step: Step, issues: MutableList<PlanIssue>): Boolean {
        var isValid = true

        if (step.metadata.name.isBlank()) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "STEP_NAME_BLANK",
                    message = "Step metadata name must not be blank",
                    stepId = step.metadata.id
                )
            )
            isValid = false
        }

        if (step.task.name.isBlank()) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "TASK_NAME_BLANK",
                    message = "Task name must not be blank",
                    stepId = step.metadata.id
                )
            )
            isValid = false
        }

        return isValid
    }
}

