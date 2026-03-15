package carp.dsp.core.application.execution

import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.StepRunResult
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.plan.PlannedStep


/**
 * Executes a single [PlannedStep].
 *
 * Implementations handle directory preparation, command execution, artefact recording,
 * and result collection.
 */
interface StepRunner {
    /**
     * Execute a single step and return complete result.
     *
     * @param step The step to execute
     * @param workspace The workspace containing all step directories
     * @param policy Controls timeouts, retries, and failure behaviour
     * @return Complete result with outputs, logs, and status
     */
    fun run(
        step: PlannedStep,
        workspace: ExecutionWorkspace,
        policy: RunPolicy = CommandPolicy()
    ): StepRunResult
}
