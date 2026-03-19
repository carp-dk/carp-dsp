package carp.dsp.core.testing

import dk.cachet.carp.analytics.application.execution.ArtefactMetadata
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.StepInfo
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.common.application.UUID


/**
 * Test double for [WorkspaceManager] that records all calls made to it.
 *
 * Useful for verifying that workspace operations are called with expected arguments.
 */
class RecordingWorkspaceManager : WorkspaceManager
{
    val createCalls = mutableListOf<Pair<ExecutionPlan, UUID>>()

    override fun create( plan: ExecutionPlan, runId: UUID ): ExecutionWorkspace
    {
        createCalls += plan to runId
        val stepInfos = plan.steps.mapIndexed { index, step ->
            step.metadata.id to StepInfo(
                id = step.metadata.id,
                name = step.metadata.name,
                executionIndex = index
            )
        }.toMap()
        return ExecutionWorkspace(
            runId = runId,
            executionRoot = runId.toString(),
            workflowName = "test-workflow",
            stepInfos = stepInfos
        )
    }

    override fun prepareStepDirectories( workspace: ExecutionWorkspace, stepId: UUID )
    {
        // No-op for testing
    }

    /**
     * Returns a path string; CommandStepRunner uses it as the working dir.
     * With a StubCommandRunner no real process is spawned so the path need not exist.
     */
    override fun resolveStepWorkingDir( workspace: ExecutionWorkspace, stepId: UUID ): String =
        "${workspace.executionRoot}/$stepId"
}


/**
 * Test double for [CommandRunner] that returns a fixed result.
 *
 * Useful for testing workflows that should succeed or fail with specific exit codes.
 */
class StubCommandRunner(
    private val exitCode: Int = 0,
    private val stdout: String = "",
    private val stderr: String = "",
    private val timedOut: Boolean = false
) : CommandRunner
{
    override fun run( command: CommandSpec, policy: RunPolicy ): CommandResult =
        CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = 1L,
            timedOut = timedOut
        )
}


/**
 * Test double for [CommandRunner] that records every command executed.
 *
 * Useful for verifying that the correct commands are being executed with expected arguments.
 */
class CapturingCommandRunner( private val exitCode: Int = 0 ) : CommandRunner
{
    val capturedCommands = mutableListOf<CommandSpec>()

    override fun run( command: CommandSpec, policy: RunPolicy ): CommandResult
    {
        capturedCommands += command
        return CommandResult(
            exitCode = exitCode,
            stdout = "",
            stderr = "",
            durationMs = 1L,
            timedOut = false
        )
    }
}


/**
 * Test double for [CommandRunner] that throws an exception.
 *
 * Useful for testing exception handling paths in execution orchestration.
 *
 * @param exception The exception to throw when run() is called.
 */
class ThrowingCommandRunner( private val exception: Exception ) : CommandRunner
{
    override fun run( command: CommandSpec, policy: RunPolicy ): CommandResult
    {
        throw exception
    }
}


/**
 * Test double for [ArtefactStore] that does not persist artefacts.
 *
 * Useful for testing workflows where artefact storage is not the focus.
 */
class StubArtefactStore : ArtefactStore
{
    override fun recordArtefact(
        stepId: UUID,
        outputId: UUID,
        location: ResourceRef,
        metadata: ArtefactMetadata
    ): ProducedOutputRef =
        ProducedOutputRef(
            outputId = outputId,
            location = location,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
            contentType = metadata.contentType
        )

    override fun getArtefact( outputId: UUID ): ProducedOutputRef? = null

    override fun getArtefactsByStep( stepId: UUID ): List<ProducedOutputRef> = emptyList()

    override fun getAllArtefacts(): List<ProducedOutputRef> = emptyList()

    override fun resolvePath( outputId: UUID ): String? = null
}


/**
 * Test double for [EnvironmentOrchestrator] with configurable behaviour.
 *
 * Defaults to returning true for setup/teardown and wrapping commands with a prefix.
 * Records all calls made to it for verification.
 *
 * Useful for testing environment orchestration without needing real environment managers.
 *
 * @param setupResult The value returned by setup() calls.
 * @param teardownResult The value returned by teardown() calls.
 * @param commandPrefix The prefix added when generating execution commands.
 */
class StubEnvironmentOrchestrator(
    private val setupResult: Boolean = true,
    private val teardownResult: Boolean = true,
    private val commandPrefix: String = "stub-env"
) : EnvironmentOrchestrator
{
    /** Environment IDs for which setup was called. */
    val setupCalls = mutableListOf<String>()

    /** Environment IDs for which teardown was called. */
    val teardownCalls = mutableListOf<String>()

    override fun setup( environmentRef: EnvironmentRef ): Boolean
    {
        setupCalls += environmentRef.id
        return setupResult
    }

    override fun teardown( environmentRef: EnvironmentRef ): Boolean
    {
        teardownCalls += environmentRef.id
        return teardownResult
    }

    override fun generateExecutionCommand( environmentRef: EnvironmentRef, command: String ): String =
        "$commandPrefix $command"
}


/**
 * Test double for [EnvironmentOrchestrator] that throws an exception.
 *
 * Useful for testing exception handling during environment setup/teardown.
 *
 * @param onSetup If true, throws on setup() calls; if false, throws on teardown() calls.
 * @param exception The exception to throw.
 */
class ThrowingEnvironmentOrchestrator(
    private val onSetup: Boolean = false,
    private val exception: Exception
) : EnvironmentOrchestrator
{
    override fun setup( environmentRef: EnvironmentRef ): Boolean
    {
        if ( onSetup ) throw exception
        return true
    }

    override fun teardown( environmentRef: EnvironmentRef ): Boolean
    {
        if ( !onSetup ) throw exception
        return true
    }

    override fun generateExecutionCommand( environmentRef: EnvironmentRef, command: String ): String =
        command
}
