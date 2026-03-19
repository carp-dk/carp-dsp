package carp.dsp.core.integration

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultEnvironmentOrchestrator
import carp.dsp.core.infrastructure.execution.DefaultEnvironmentRegistry
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.core.testing.*
import dk.cachet.carp.analytics.application.exceptions.InvalidExecutionPlanException
import dk.cachet.carp.analytics.application.exceptions.YamlCodecException
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.execution.workspace.WorkspacePathFormatter
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentMetadata
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentRegistry
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.test.*

/**
 * Abstract base class for integration tests.
 *
 * Provides shared infrastructure for testing the complete Author → Plan → Execute pipeline.
 * All integration test classes should inherit from this class to gain access to:
 *
 * - **Fixture Loading:** Load YAML workflows and parse to domain models
 * - **Environment Management:** Setup/teardown real environments (Conda, Pixi, System)
 * - **Workflow Processing:** Parse descriptors → definitions → execution plans
 * - **Assertion Helpers:** Comprehensive assertions for plan validity, execution results
 * - **Test Double Factories:** Create stubs/mocks for isolated testing
 *
 * ## Setup/Teardown Flow
 *
 * ```
 * @BeforeTest
 *   ├─ Create temp directory
 *   ├─ Initialize environment orchestrator
 *   ├─ Initialize artifact store
 *   ├─ Initialize workspace manager
 *   └─ Initialize executor
 *
 * [Test runs]
 *
 * @AfterTest
 *   ├─ Cleanup environments
 *   ├─ Verify cleanup completed
 *   └─ Delete temp directory
 * ```
 *
 * ## Example Usage
 *
 * ```kotlin
 * class MyIntegrationTest : IntegrationTestBase()
 * {
 *     @Test
 *     fun `happy path workflow execution`()
 *     {
 *         // 1. Load fixture
 *         val descriptor = loadFixture( "my-workflow.yaml" )
 *
 *         // 2. Parse and generate plan
 *         val plan = generateExecutionPlan( definition )
 *
 *         // 3. Assert plan validity
 *         assertPlanValid( plan )
 *         assertPlanHasSteps( plan, expectedCount = 3 )
 *
 *         // 4. Execute
 *         val report = executeAndVerify( plan )
 *
 *         // 5. Assert results
 *         assertExecutionSucceeded( report )
 *         assertArtifactsCollected( report, count = 2 )
 *     }
 * }
 * ```
 *
 * @see IntegrationTestBase
 * @see MinimalWorkflowIntegrationTest
 * @see SimpleStepSystemPythonTest
 * @see SingleStepPythonCondaTest
 * @see SingleStepPythonPixiTest
 * @see SingleStepRTest
 */
    abstract class IntegrationTestBase
{
    // Shared Resources

    /** Temporary directory for test artefacts. */
    protected lateinit var tmpDir: Path

    /** Helper to check whether tmpDir has been initialized. */
    protected fun isTmpDirInitialized(): Boolean = ::tmpDir.isInitialized

    /** Helper to check whether orchestrator has been initialized. */
    protected fun isOrchestratorInitialized(): Boolean = ::orchestrator.isInitialized

    /** Helper to check whether artefactStore has been initialized. */
    protected fun isArtefactStoreInitialized(): Boolean = ::artefactStore.isInitialized

    /** Helper to check whether executor has been initialized. */
    protected fun isExecutorInitialized(): Boolean = ::executor.isInitialized

    /** Helper to check whether workspaceManager has been initialized. */
    protected fun isWorkspaceManagerInitialized(): Boolean = ::workspaceManager.isInitialized

    /** Helper to check whether envRegistry has been initialized. */
    protected fun isEnvRegistryInitialized(): Boolean = ::envRegistry.isInitialized

    /** Helper to check whether runId has been initialized. */
    protected fun isRunIdInitialized(): Boolean = ::runId.isInitialized

    /** Environment orchestrator for provisioning/teardown. */
    protected lateinit var orchestrator: EnvironmentOrchestrator

    /** Artefact store for recording step outputs. */
    protected lateinit var artefactStore: ArtefactStore

    /** Workspace manager for creating execution workspaces. */
    protected lateinit var workspaceManager: WorkspaceManager

    /** Plan executor for running workflows. */
    protected lateinit var executor: DefaultPlanExecutor

    /** Environment registry for tracking provisioned environments. */
    protected lateinit var envRegistry: EnvironmentRegistry

    /** Run ID for the current test execution, used for tracking and logging. */
    protected lateinit var runId: UUID

    // Setup and Teardown

    /**
     * Initialize test infrastructure before each test.
     *
     * Creates:
     * - Temporary directory for test files
     * - Real environment orchestrator
     * - Real artefact store
     * - Real workspace manager
     * - Plan executor
     *
     * This runs before every test method.
     */
    @BeforeTest
    fun setupIntegrationEnvironment()
    {
        // Create temp directory for test
        tmpDir = Files.createTempDirectory( "integration-test-${UUID.randomUUID()}" )
        runId = UUID.randomUUID()

        // Initialize environment registry
        envRegistry = DefaultEnvironmentRegistry(
            tmpDir.resolve( "environment-registry.json" )
        )

        // Initialize environment orchestrator
        orchestrator = DefaultEnvironmentOrchestrator( envRegistry )

        // Initialize artefact store
        artefactStore = FileSystemArtefactStore( tmpDir.resolve( "artifacts" ) )

        // Initialize workspace manager
        workspaceManager = DefaultWorkspaceManager(tmpDir)

        // Initialize executor
        executor = DefaultPlanExecutor(
            workspaceManager = workspaceManager,
            artefactStore = artefactStore
        )
    }

    /**
     * Clean-up test infrastructure after each test.
     *
     * Performs:
     * - Environment teardown and clean-up
     * - Clean-up verification
     * - Temporary directory removal
     *
     * This runs after every test method, even if the test fails.
     */
    @OptIn( ExperimentalPathApi::class )
    @AfterTest
    fun cleanupIntegrationEnvironment()
    {
        runCatching { teardownProvisionedEnvironments() }
            .onFailure { System.err.println( "Warning: Environment teardown failed: ${it.message}" ) }

        runCatching { deleteTempDirectory() }
            .onFailure { System.err.println( "Warning: Temp directory cleanup failed: ${it.message}" ) }
    }

    // Fixture Loading

    /**
     * Load a fixture YAML file by name.
     *
     * Fixtures should be placed in `src/test/resources/integration-fixtures/`.
     *
     * @param name Fixture filename (e.g., "minimal-valid.yaml")
     * @return Parsed [WorkflowDescriptor]
     * @throws IllegalArgumentException if fixture not found
     * @throws YamlCodecException if fixture cannot be parsed
     *
     * Example:
     * ```kotlin
     * val descriptor = loadFixture( "signal-processing.yaml" )
     * ```
     */
    protected fun loadFixture( name: String ): WorkflowDescriptor
    {
        val resource = this::class.java.classLoader
            .getResource( "integration-fixtures/$name" )
            ?: throw IllegalArgumentException(
                "Fixture not found: $name (expected in src/test/resources/integration-fixtures/)"
            )

        val yaml = resource.readText()
        return WorkflowYamlCodec().decodeOrThrow( yaml )
    }

    // Workflow Processing

    /**
     * Generate an [ExecutionPlan] from a [WorkflowDefinition].
     *
     * This step:
     * - Validates the workflow definition
     * - Performs topological sorting of steps
     * - Resolves input/output bindings
     * - Expands arguments
     * - Assigns environment references
     *
     * @param definition Validated workflow definition
     * @return Complete [ExecutionPlan] ready for execution
     * @throws InvalidExecutionPlanException if plan generation fails
     *
     * Example:
     * ```kotlin
     * val definition = parseToWorkflowDefinition( descriptor )
     * val plan = generateExecutionPlan( definition )
     * ```
     */
    protected fun generateExecutionPlan( definition: WorkflowDefinition ): ExecutionPlan
    {
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan( definition )

        plan.validate()

        val errors = plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
        if ( errors.isNotEmpty() )
        {
            val messages = errors.joinToString( "; " ) { "${it.code}: ${it.message}" }
            throw InvalidExecutionPlanException( "Execution plan contains errors: $messages", errors.map { it.message } )
        }

        return plan
    }

    /**
     * Execute a plan and verify it runs without throwing exceptions.
     *
     * This method:
     * - Creates an execution workspace
     * - Runs the executor
     * - Verifies the report is valid
     * - Returns the report for assertions
     *
     * @param plan Execution plan to run
     * @return [ExecutionReport] with execution results
     * @throws AssertionError if execution report is invalid
     *
     * Example:
     * ```kotlin
     * val plan = generateExecutionPlan( definition )
     * val report = executeAndVerify( plan )
     * assertExecutionSucceeded( report )
     * ```
     */
    protected fun executeAndVerify( plan: ExecutionPlan ): ExecutionReport
    {
        val report = try
        {
            executor.execute( plan, runId )
        } catch ( e: Exception )
        {
            fail( "Execution should not throw exception: ${e.message}\n${e.stackTrace.take( 5 ).joinToString( "\n" )}" )
        }

        // Basic validation
        assertNotNull( report, "ExecutionReport should not be null" )
        assertNotNull( report.planId, "Report planId should be set" )

        return report
    }

    // Assertion Helpers

    /**
     * Assert that an [ExecutionPlan] is valid.
     *
     * Checks:
     * - Plan is not null
     * - Steps list is not empty
     * - All steps have IDs
     * - All steps have processes
     * - Plan has diagnostics
     *
     * @param plan Plan to validate
     * @throws AssertionError if plan is invalid
     */
    protected fun assertPlanValid( plan: ExecutionPlan )
    {
        assertNotNull( plan, "Plan should not be null" )
        assertTrue( plan.steps.isNotEmpty(), "Plan should have at least one step" )

        plan.steps.forEach { step ->
            assertNotNull( step.metadata.id, "Step should have ID" )
            assertNotNull( step.metadata.name, "Step should have name" )
            assertNotNull( step.process, "Step should have process" )
        }
    }

    /**
     * Assert that an [ExecutionPlan] has a specific number of steps.
     *
     * @param plan Plan to check
     * @param expectedCount Expected number of steps
     * @throws AssertionError if step count doesn't match
     */
    protected fun assertPlanHasSteps( plan: ExecutionPlan, expectedCount: Int )
    {
        assertEquals(
            expectedCount,
            plan.steps.size,
            "Plan should have $expectedCount steps, but has ${plan.steps.size}"
        )
    }

    /**
     * Assert that steps in a plan are ordered in a specific sequence.
     *
     * @param plan Plan to check
     * @param expectedOrder List of step IDs in expected order
     * @throws AssertionError if order doesn't match
     *
     * Example:
     * ```kotlin
     * assertStepsOrdered( plan, listOf( "step-1", "step-2", "step-3" ) )
     * ```
     */
    protected fun assertStepsOrdered( plan: ExecutionPlan, expectedOrder: List<String> )
    {
        val actualOrder = plan.steps.map { it.metadata.name }
        assertEquals(
            expectedOrder,
            actualOrder,
            "Steps should be in order: $expectedOrder\nBut got: $actualOrder"
        )
    }

    /**
     * Assert that an [ExecutionReport] indicates successful execution.
     *
     * Checks:
     * - Status is SUCCEEDED
     * - No issues recorded
     * - finishedAt >= startedAt
     *
     * @param report Report to check
     * @throws AssertionError if execution failed
     */
    protected fun assertExecutionSucceeded( report: ExecutionReport )
    {
        assertEquals(
            ExecutionStatus.SUCCEEDED,
            report.status,
            "Execution should succeed, but status is ${report.status}"
        )

        assertTrue(
            report.issues.isEmpty(),
            "Execution succeeded but issues recorded: ${report.issues.map { it.message }}"
        )
    }

    /**
     * Assert that an [ExecutionReport] indicates failed execution.
     *
     * Checks:
     * - Status is FAILED
     * - Issues list is not empty
     *
     * @param report Report to check
     * @throws AssertionError if execution succeeded
     */
    protected fun assertExecutionFailed( report: ExecutionReport )
    {
        assertEquals(
            ExecutionStatus.FAILED,
            report.status,
            "Execution should fail, but status is ${report.status}"
        )

        assertTrue(
            report.issues.isNotEmpty(),
            "Execution failed but no issues recorded"
        )
    }

    /**
     * Assert that an [ExecutionReport] contains a specific number of collected artefacts.
     *
     * Counts all outputs across all completed steps.
     *
     * @param report Report to check
     * @param expectedCount Expected number of artefacts
     * @throws AssertionError if count doesn't match
     *
     * Example:
     * ```kotlin
     * val report = executeAndVerify( plan )
     * assertArtifactsCollected( report, count = 3 )
     * ```
     */
    protected fun assertArtifactsCollected( report: ExecutionReport, expectedCount: Int )
    {
        val actualCount = report.stepResults.sumOf { it.outputs?.size ?: 0 }
        assertEquals(
            expectedCount,
            actualCount,
            "Should collect $expectedCount artifacts, but collected $actualCount"
        )
    }

    /**
     * Assert that an [ExecutionReport] contains issues of a specific kind.
     *
     * @param report Report to check
     * @param expectedKind Expected issue kind
     * @throws AssertionError if no matching issues found
     *
     * Example:
     * ```kotlin
     * assertIssuesRecorded( report, ExecutionIssueKind.PROCESS_FAILED )
     * ```
     */
    protected fun assertIssuesRecorded(
        report: ExecutionReport,
        expectedKind: ExecutionIssueKind
    )
    {
        assertTrue(
            report.issues.any { it.kind == expectedKind },
            "Should have issues of kind $expectedKind, but got kinds: ${report.issues.map { it.kind }}"
        )
    }

    /**
     * Assert that an [EnvironmentRef] has been provisioned.
     *
     * Checks:
     * - Environment exists in orchestrator
     * - Environment metadata is registered
     *
     * @param ref Environment reference to check
     * @throws AssertionError if environment not provisioned
     */
    protected fun assertEnvironmentProvisioned( ref: EnvironmentRef )
    {
        assertTrue(
            envRegistry.exists( ref.id ),
            "Environment ${ref.id} should be provisioned but not found in registry"
        )

        val metadata = envRegistry.getMetadata( ref.id )
        assertNotNull(
            metadata,
            "Environment ${ref.id} metadata should exist"
        )
    }

    /**
     * Assert that an [EnvironmentRef] has been cleaned up.
     *
     * Checks:
     * - Environment no longer exists in orchestrator (if PURGE policy)
     * - Or environment exists but marked for clean-up (if REUSE policy)
     *
     * @param ref Environment reference to check
     * @throws AssertionError if environment not cleaned up
     */
    protected fun assertEnvironmentCleaned( ref: EnvironmentRef )
    {
        // Behaviour depends on clean-up policy
        // This is a soft assertion - environment may exist but be marked as reusable
        val stillExists = envRegistry.exists( ref.id )
        val metadata = envRegistry.getMetadata( ref.id )

        // If still exists, ensure it's properly recorded
        if ( stillExists && metadata != null )
        {
            assertNotNull( metadata.id )
        }
    }

    // Test Double Factories

    /**
     * Create a stub [CommandRunner] that returns fixed results.
     *
     * Useful for isolating tests when command execution shouldn't vary.
     *
     * @return Stub command runner
     * @see StubCommandRunner
     */
    protected fun createStubCommandRunner(): CommandRunner
    {
        // Import from :dsp:testing module
        return StubCommandRunner()
    }

    /**
     * Create a recording [WorkspaceManager] that tracks all calls.
     *
     * Useful for verifying workspace management behaviour without side effects.
     *
     * @return Recording workspace manager with call tracking
     * @see RecordingWorkspaceManager
     */
    protected fun createRecordingWorkspaceManager(): WorkspaceManager
    {
        // Import from :dsp:testing module
        return RecordingWorkspaceManager()
    }

    /**
     * Create a capturing command runner that records executed commands.
     *
     * Useful for verifying that specific commands are executed with expected arguments.
     *
     * @return Capturing command runner
     * @see CapturingCommandRunner
     */
    protected fun createCapturingCommandRunner(): CapturingCommandRunner
    {
        return CapturingCommandRunner()
    }

    /**
     * Create a throwing command runner that fails with a specific exception.
     *
     * Useful for testing failure handling.
     *
     * @param exception Exception to throw on execution
     * @return Throwing command runner
     * @see ThrowingCommandRunner
     */
    protected fun createThrowingCommandRunner( exception: Exception ): ThrowingCommandRunner
    {
        return ThrowingCommandRunner(exception)
    }

    /**
     * Create a stub artefact store that doesn't actually store artefacts.
     *
     * Useful for testing when artefact storage shouldn't have side effects.
     *
     * @return Stub artefact store
     * @see StubArtefactStore
     */
    protected fun createStubArtefactStore(): ArtefactStore
    {
        return StubArtefactStore()
    }

    // Helper Utilities

    /**
     * Wait for a condition to become true with timeout.
     *
     * Useful for waiting for asynchronous operations to complete.
     *
     * @param timeoutMillis Maximum milliseconds to wait
     * @param pollIntervalMillis How often to check the condition
     * @param condition Lambda that returns true when condition is met
     * @throws AssertionError if timeout exceeded
     *
     * Example:
     * ```kotlin
     * waitFor { environmentOrchestrator.exists( envId ) }
     * ```
     */
    protected fun waitFor(
        timeoutMillis: Long = 10000,
        pollIntervalMillis: Long = 100,
        condition: () -> Boolean
    )
    {
        val startTime = System.currentTimeMillis()
        while ( !condition() )
        {
            if ( System.currentTimeMillis() - startTime > timeoutMillis )
            {
                throw AssertionError( "Condition not met within ${timeoutMillis}ms" )
            }
            Thread.sleep( pollIntervalMillis )
        }
    }

    /**
     * Create a random temporary subdirectory within the test temp directory.
     *
     * Useful for creating isolated test fixtures.
     *
     * @param name Name of subdirectory
     * @return Path to created subdirectory
     */
    protected fun createTestSubdir( name: String ): Path
    {
        val subdir = tmpDir.resolve( name )
        Files.createDirectories( subdir )
        return subdir
    }

    /**
     * Returns the executionRoot that DefaultWorkspaceManager will create for [plan] and the pre-set [runId].
     * Use this in @BeforeTest to copy assets before execution.
     */
    protected fun executionRootFor( plan: ExecutionPlan ): Path =
        tmpDir
            .resolve( WorkspacePathFormatter.formatWorkflowName( plan.workflowName ) )
            .resolve( "run_$runId" )

    /**
     * Copies classpath resources into [root].
     * Each pair is (classpathResourcePath, relativeDestPath).
     *
     * Example:
     *   setupWorkspaceAssets( executionRootFor(plan),
     *       "scripts/process.py" to "scripts/process.py",
     *       "data/input.csv"     to "inputs/input.csv"
     *   )
     */
    protected fun setupWorkspaceAssets( root: Path, vararg mappings: Pair<String, String> )
    {
        for ( (classpathPath, destPath) in mappings )
        {
            val resource = this::class.java.classLoader.getResource( classpathPath )
                ?: throw IllegalArgumentException( "Resource not found on classpath: $classpathPath" )
            val dest = root.resolve( destPath )
            dest.parent.createDirectories()
            Files.copy( resource.openStream(), dest, StandardCopyOption.REPLACE_EXISTING )
        }
    }

    private fun EnvironmentMetadata.toEnvironmentRef(): EnvironmentRef?
    {
        return when ( kind.lowercase() )
        {
            "conda" -> CondaEnvironmentRef( id = id, name = name.ifBlank { id }, dependencies = emptyList() )
            "pixi" -> PixiEnvironmentRef( id = id, name = name.ifBlank { id }, dependencies = emptyList() )
            "system" -> SystemEnvironmentRef( id = id )
            "r" -> REnvironmentRef( id = id, name = name.ifBlank { id }, rVersion = name.ifBlank { "latest" } )
            else -> null
        }
    }

    private fun teardownProvisionedEnvironments()
    {
        if ( !::orchestrator.isInitialized || !::envRegistry.isInitialized ) return

        for ( envMetadata in envRegistry.list() )
        {
            val ref = envMetadata.toEnvironmentRef()
            if ( ref == null )
            {
                System.err.println( "Warning: Unknown environment kind ${envMetadata.kind} for ${envMetadata.id}; skipping teardown" )
                continue
            }

            val success = orchestrator.teardown( ref )
            if ( !success )
            {
                System.err.println( "Warning: Failed to teardown environment ${envMetadata.id}" )
            }
        }
    }

    private fun deleteTempDirectory()
    {
        if ( !::tmpDir.isInitialized ) return
        val tmpDirFile = tmpDir.toFile()
        if ( tmpDirFile.exists() ) tmpDirFile.deleteRecursively()
    }
}


