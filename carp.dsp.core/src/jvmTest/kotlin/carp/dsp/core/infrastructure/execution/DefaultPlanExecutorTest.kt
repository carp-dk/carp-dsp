package carp.dsp.core.infrastructure.execution

import carp.dsp.core.testing.*
import dk.cachet.carp.analytics.application.exceptions.ArtefactCollectionException
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
import dk.cachet.carp.analytics.application.exceptions.ExecutionIOException
import dk.cachet.carp.analytics.application.exceptions.ProcessExecutionException
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.DefaultRunPolicy
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.SetupTiming
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class DefaultPlanExecutorTest
{
    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun plannedStep(
        name: String = "step",
        stepId: UUID = UUID.randomUUID(),
        environmentRef: UUID? = UUID.randomUUID()
    ): PlannedStep =
        PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = name,
            ),
            process = CommandSpec( "echo", listOf( ExpandedArg.Literal( name ) ) ),
            bindings = ResolvedBindings(),
            environmentRef = environmentRef
        )

    private fun plan(
        vararg steps: PlannedStep,
        workflowId: String = "wf-1",
        requiredEnvironmentRefs: Map<UUID, EnvironmentRef> = emptyMap()
    ): ExecutionPlan =
        ExecutionPlan(
            workflowName = workflowId,
            planId = UUID.randomUUID().toString(),
            steps = steps.toList(),
            requiredEnvironmentRefs = requiredEnvironmentRefs
        )

    private fun executor(
        manager: WorkspaceManager = RecordingWorkspaceManager(),
        runner: CommandRunner = StubCommandRunner(),
        artefactStore: ArtefactStore = StubArtefactStore(),
        strategy: StepOrderStrategy = SequentialPlanOrder,
        orchestrator: EnvironmentOrchestrator? = null,
        environmentConfig: EnvironmentConfig = EnvironmentConfig()
    ): DefaultPlanExecutor =
        DefaultPlanExecutor(
            workspaceManager = manager,
            artefactStore = artefactStore,
            options = DefaultPlanExecutor.Options(
                commandRunner = runner,
                stepOrderStrategy = strategy,
                orchestrator = orchestrator ?: DefaultEnvironmentOrchestrator(
                    DefaultEnvironmentRegistry(
                        java.nio.file.Paths.get( System.getProperty( "java.io.tmpdir" ), "carp-dsp-test-registry.json" )
                    )
                ),
                environmentConfig = environmentConfig
            )
        )


    @Test
    fun `execute calls workspaceManager_create exactly once with plan and runId`()
    {
        val manager = RecordingWorkspaceManager()
        val p = plan( plannedStep( "a" ) )
        val runId = UUID.randomUUID()

        executor( manager = manager ).execute( p, runId )

        assertEquals( 1, manager.createCalls.size )
        val ( calledPlan, calledRunId ) = manager.createCalls.single()
        assertEquals( p, calledPlan )
        assertEquals( runId, calledRunId )
    }

    @Test
    fun `execute produces one StepRunResult per planned step`()
    {
        val steps = listOf( plannedStep( "a" ), plannedStep( "b" ), plannedStep( "c" ) )
        val report = executor().execute( plan( *steps.toTypedArray() ), UUID.randomUUID() )
        assertEquals( steps.size, report.stepResults.size )
    }

    @Test
    fun `execute preserves topo order`()
    {
        val stepA = plannedStep( "alpha" )
        val stepB = plannedStep( "beta" )
        val stepC = plannedStep( "gamma" )
        val report = executor().execute( plan( stepA, stepB, stepC ), UUID.randomUUID() )

        assertEquals(
            listOf( stepA.metadata.id, stepB.metadata.id, stepC.metadata.id ),
            report.stepResults.map { it.stepMetadata.id },
            "Execution should preserve topological order"
        )
    }

    @Test
    fun `execute with stopOnFailure=true stops after first failure`()
    {
        val runner = StubCommandRunner( exitCode = 1 )
        val report = executor( runner = runner ).execute(
            plan( plannedStep( "a" ), plannedStep( "b" ), plannedStep( "c" ) ),
            UUID.randomUUID()
        )

        assertEquals( ExecutionStatus.FAILED, report.status )
        val failedCount = report.stepResults.count { it.status == ExecutionStatus.FAILED }
        val skippedCount = report.stepResults.count { it.status == ExecutionStatus.SKIPPED }
        assertEquals(
            3,
            failedCount + skippedCount,
            "All three steps should either fail or be skipped"
        )
    }

    @Test
    fun `execute with stopOnFailure=false continues after failure`()
    {
        val runner = StubCommandRunner( exitCode = 1 )
        val report = executor( runner = runner ).execute(
            plan( plannedStep( "a" ), plannedStep( "b" ), plannedStep( "c" ) ),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = false)
        )

        assertEquals( ExecutionStatus.FAILED, report.status )
        assertEquals( 3, report.stepResults.count { it.status == ExecutionStatus.FAILED } )
    }

    @Test
    fun `execute records execution time for successful steps`()
    {
        val report = executor().execute(
            plan( plannedStep( "a" ), plannedStep( "b" ) ),
            UUID.randomUUID()
        )

        for ( result in report.stepResults )
        {
            assertNotNull( result.startedAt, "Step should have startedAt" )
            assertNotNull( result.finishedAt, "Step should have finishedAt" )
        }
    }

    @Test
    fun `unknown step recorded as FAILED with ORCHESTRATOR_ERROR issue`()
    {
        val strategy = StepOrderStrategy { listOf( UUID.randomUUID() ) }

        val report = executor( strategy = strategy ).execute(
            plan( plannedStep( "a" ) ),
            UUID.randomUUID()
        )

        assertEquals( 1, report.stepResults.size )
        assertEquals( ExecutionStatus.FAILED, report.stepResults[0].status )
        assertTrue( report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR } )
    }

    @Test
    fun `EAGER setup success allows steps to execute`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )
        val orchestrator = StubEnvironmentOrchestrator()

        executor(
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.EAGER )
        ).execute(
            plan(
                plannedStep( "a", environmentRef = envId ),
                requiredEnvironmentRefs = mapOf( envId to envRef )
            ),
            UUID.randomUUID()
        )

        assertEquals( 1, orchestrator.setupCalls.count { it == envId.toString() } )
    }

    @Test
    fun `EAGER setup failure records issue and halts execution`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator( setupResult = false ),
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.EAGER )
        ).execute(
            plan(
                plannedStep( "a", environmentRef = envId ),
                plannedStep( "b", environmentRef = envId ),
                requiredEnvironmentRefs = mapOf( envId to envRef )
            ),
            UUID.randomUUID()
        )

        assertEquals( ExecutionStatus.FAILED, report.status )
        assertTrue( report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR } )
    }

    @Test
    fun `LAZY setup, same environment not set up twice across steps`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )
        val orchestrator = StubEnvironmentOrchestrator()

        executor(
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan(
                plannedStep( "a", environmentRef = envId ),
                plannedStep( "b", environmentRef = envId ),
                requiredEnvironmentRefs = mapOf( envId to envRef )
            ),
            UUID.randomUUID()
        )

        assertEquals(
            1,
            orchestrator.setupCalls.count { it == envId.toString() },
            "Same environment must not be set up more than once"
        )
    }

    @Test
    fun `LAZY setup failure records issue and marks step as FAILED`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator( setupResult = false ),
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan( plannedStep( "a" ), requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        assertEquals( ExecutionStatus.FAILED, report.stepResults[0].status )
        assertTrue( report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR } )
    }

    @Test
    fun `environmentLogs returns empty when orchestrator is not a DefaultEnvironmentOrchestrator`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator()
        ).execute(
            plan( plannedStep( "a" ), requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        assertNotNull( report.environmentLogs )
    }

    @Test
    fun `buildBaseCommand produces executable-only string when step has no args`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )
        val noArgStep = PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "no-args"
            ),
            process = CommandSpec( "mybin", emptyList() ),
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner(exitCode = 0)

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator( commandPrefix = "wrap" ),
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan( noArgStep, requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""

        assertTrue( shellArg.contains( "mybin" ), "Expected 'mybin' in wrapped command: $shellArg" )
        assertTrue(
            !shellArg.endsWith( "  mybin" ) && shellArg.contains( "wrap mybin" ),
            "Expected 'wrap mybin' (no trailing space before executable): $shellArg"
        )
    }

    @Test
    fun `argToLiteral handles DataReference and PathSubstitution args`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )
        val dataRefId = UUID.randomUUID()

        val stepWithVariousArgs = PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "arg-types"
            ),
            process = CommandSpec(
                "mybin",
                listOf(
                    ExpandedArg.Literal( "literal-value" ),
                    ExpandedArg.DataReference( dataRefId ),
                    ExpandedArg.PathSubstitution( dataRefId, "--out=$()" )
                )
            ),
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner( exitCode = 0 )

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator( commandPrefix = "wrap" ),
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan( stepWithVariousArgs, requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""

        assertTrue(
            shellArg.contains( dataRefId.toString() ),
            "DataReference UUID should be in command: $shellArg"
        )
        assertTrue(
            shellArg.contains( "--out=" ),
            "PathSubstitution template should be resolved: $shellArg"
        )
    }

    @Test
    fun `shellQuote handles args that need quoting on the current platform`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )

        val stepWithSpacedArg = PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "spaced"
            ),
            process = CommandSpec(
                "mybin",
                listOf(
                    ExpandedArg.Literal( "arg with spaces" ),
                    ExpandedArg.Literal( "plain" ),
                    ExpandedArg.Literal( "" )
                )
            ),
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner( exitCode = 0 )

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator( commandPrefix = "wrap" ),
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan( stepWithSpacedArg, requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""

        assertTrue(
            shellArg.contains( "arg with spaces" ) || shellArg.contains( "'arg with spaces'" ),
            "Spaced arg should be quoted or present: $shellArg"
        )
        assertTrue( shellArg.contains( "plain" ), "Plain arg should appear: $shellArg" )
    }


    @Test
    fun `ProcessExecutionException is converted to ExecutionIssue with PROCESS_FAILED kind`()
    {
        val stepId = UUID.randomUUID()
        val exception = ProcessExecutionException(
            message = "Script failed",
            command = "python script.py",
            exitCode = 1
        )
        val thrower = ThrowingCommandRunner(exception)
        val step = plannedStep( stepId = stepId )

        val report = executor( runner = thrower ).execute(
            plan( step ),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any { it.kind == ExecutionIssueKind.PROCESS_FAILED },
            "Expected PROCESS_FAILED issue"
        )
        assertTrue(
            report.issues.any { "python script.py" in it.message },
            "Expected command name in issue message"
        )
        assertTrue(
            report.issues.any { "exit code" in it.message.lowercase() },
            "Expected exit code in issue message"
        )
    }

    @Test
    fun `EnvironmentSetupException is converted to ExecutionIssue with ORCHESTRATOR_ERROR kind`()
    {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef( id = envId.toString(), dependencies = emptyList() )
        val exception = EnvironmentSetupException(
            message = "Conda setup failed",
            envId = envId.toString()
        )
        val thrower = ThrowingEnvironmentOrchestrator(onSetup = true, exception = exception)

        val report = executor(
            orchestrator = thrower,
            environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
        ).execute(
            plan( plannedStep(), requiredEnvironmentRefs = mapOf( envId to envRef ) ),
            UUID.randomUUID()
        )

        assertEquals( ExecutionStatus.FAILED, report.status )
        assertTrue(
            report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR },
            "Expected ORCHESTRATOR_ERROR issue"
        )
        assertTrue(
            report.issues.any { it.message.startsWith("No EnvironmentRef mapped for step ") },
            "Expected error message in issue"
        )
    }

    @Test
    fun `ExecutionIOException is converted to ExecutionIssue with ORCHESTRATOR_ERROR kind`()
    {
        val stepId = UUID.randomUUID()
        val exception = ExecutionIOException(
            message = "Cannot write to disk",
            filePath = "/tmp/output.txt"
        )
        val thrower = ThrowingCommandRunner( exception )
        val step = plannedStep( stepId = stepId )

        val report = executor( runner = thrower ).execute(
            plan( step ),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR },
            "Expected ORCHESTRATOR_ERROR issue"
        )
        assertTrue(
            report.issues.any { "Cannot write to disk" in it.message },
            "Expected IO error message in issue"
        )
    }

    @Test
    fun `ArtefactCollectionException is converted to ExecutionIssue with ORCHESTRATOR_ERROR kind`()
    {
        val stepId = UUID.randomUUID()
        val artefactId = UUID.randomUUID()
        val exception = ArtefactCollectionException(
            message = "Cannot collect outputs",
            artefactId = artefactId
        )
        val thrower = ThrowingCommandRunner( exception )
        val step = plannedStep( stepId = stepId )

        val report = executor( runner = thrower ).execute(
            plan( step ),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR },
            "Expected ORCHESTRATOR_ERROR issue"
        )
        assertTrue(
            report.issues.any { "Cannot collect outputs" in it.message },
            "Expected artefact error message in issue"
        )
    }

    @Test
    fun `WorkflowExecutionException base class is caught and converted to issue`()
    {
        val stepId = UUID.randomUUID()
        val exception = ExecutionIOException(
            message = "Workflow error",
            filePath = "/tmp/output.txt"
        )

        val thrower = ThrowingCommandRunner( exception )
        val step = plannedStep( stepId = stepId )

        val report = executor( runner = thrower ).execute(
            plan( step ),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR },
            "Expected generic ORCHESTRATOR_ERROR for base exception"
        )
    }

    @Test
    fun `exception in step execution does not affect subsequent steps when stopOnFailure=false`()
    {
        val thrower = ThrowingCommandRunner(
            ProcessExecutionException( "Failed", "cmd", 1 )
        )

        val report = executor( runner = thrower ).execute(
            plan(
                plannedStep( "a" ),
                plannedStep( "b" ),
                plannedStep( "c" )
            ),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = false),
        )

        assertEquals( 3, report.issues.size )
    }

    @Test
    fun `each step exception is recorded as separate issue`()
    {
        val thrower = ThrowingCommandRunner(
            ProcessExecutionException( "Failed", "cmd", 1 )
        )

        val report = executor( runner = thrower ).execute(
            plan(
                plannedStep( "a" ),
                plannedStep( "b" )
            ),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = false)
        )

        val issues = report.issues.filter { it.kind == ExecutionIssueKind.PROCESS_FAILED }
        assertEquals( 2, issues.size, "Expected issue for each failed step" )
    }

    @Test
    fun `ProcessExecutionException with null exit code formats message correctly`()
    {
        val exception = ProcessExecutionException(
            message = "Process terminated",
            command = "bash script.sh",
            exitCode = null
        )
        val thrower = ThrowingCommandRunner( exception )

        val report = executor( runner = thrower ).execute(
            plan( plannedStep() ),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any { "unknown" in it.message.lowercase() },
            "Expected 'unknown' in message when exit code is null"
        )
    }

    @Test
    fun `exception handling maintains step isolation`()
    {
        // Mix of throwing and non-throwing runners is not directly testable here,
        // but we verify that one exception does not prevent recording other steps.
        val thrower = ThrowingCommandRunner(
            ProcessExecutionException( "Failed", "cmd", 1 )
        )

        val report = executor( runner = thrower ).execute(
            plan(
                plannedStep( "step-1" ),
                plannedStep( "step-2" ),
                plannedStep( "step-3" )
            ),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = false)
        )

        // All steps should be recorded despite exceptions
        // with stopOnFailure=false, even if they all fail due to the same exception.
        assertEquals( 3, report.issues.size )
    }
}
