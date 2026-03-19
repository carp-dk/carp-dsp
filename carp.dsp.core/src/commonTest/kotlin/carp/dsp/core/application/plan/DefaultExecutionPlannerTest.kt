package carp.dsp.core.application.plan

import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.*
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests cover:
 * - Empty workflows
 * - Single step workflows
 * - Multi-step workflows with dependencies
 * - Environment validation
 * - Dependency graph construction
 * - Topological sorting
 * - Step compilation
 * - Error handling and issue collection
 * - Deterministic behaviour
 */
class DefaultExecutionPlannerTest
{
    private val planner = DefaultExecutionPlanner()

    // Mock task definition for testing
    private class MockTaskDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    // Mock environment for testing
    private class MockEnvironmentDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val dependencies: List<String> = emptyList(),
        override val environmentVariables: Map<String, String> = emptyMap()
    ) : EnvironmentDefinition

    private fun createWorkflowDefinition(
        name: String = "Test Workflow",
        steps: List<Step> = emptyList(),
        environments: Map<UUID, EnvironmentDefinition> = emptyMap()
    ): WorkflowDefinition
    {
        val workflowMetadata = WorkflowMetadata(
            id = UUID.randomUUID(),
            name = name,
            description = "Test workflow: $name",
            version = Version( 1, 0 )
        )

        val workflow = Workflow( workflowMetadata )
        steps.forEach { workflow.addComponent( it ) }

        return WorkflowDefinition(
            workflow = workflow,
            environments = environments
        )
    }

    private fun createStep(
        name: String,
        environmentId: UUID = UUID.randomUUID(),
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList(),
        task: TaskDefinition? = null
    ): Step
    {
        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version( 1, 0 )
            ),
            task = task ?: MockTaskDefinition( name = "task-$name" ),
            environmentId = environmentId,
            inputs = inputs,
            outputs = outputs
        )
    }

    private fun createCommandStep(
        name: String,
        environmentId: UUID = UUID.randomUUID(),
        executable: String = "echo",
        args: List<String> = listOf( "test" )
    ): Step
    {
        val commandTask = CommandTaskDefinition(
            id = UUID.randomUUID(),
            name = "command-$name",
            executable = executable,
            args = args.map { Literal( it ) }
        )

        return createStep( name, environmentId, task = commandTask )
    }

    // ── Empty Workflow Tests ──────────────────────────────────────────────────

    @Test
    fun `plan with empty workflow returns empty execution plan`()
    {
        // Arrange
        val definition = createWorkflowDefinition( "Empty Workflow" )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertTrue( plan.steps.isEmpty() )
        assertTrue( plan.issues.isEmpty() )
        assertTrue( plan.requiredEnvironmentRefs.isEmpty() )
        assertEquals( definition.workflow.metadata.name, plan.workflowName )
        assertNotNull( plan.planId )
    }

    // ── Single Step Tests ─────────────────────────────────────────────────────

    @Test
    fun `plan with single command step succeeds`()
    {
        // Arrange
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env"
        )
        val step = createCommandStep( "single-step", env.id )
        val definition = createWorkflowDefinition(
            "Single Step Workflow",
            steps = listOf( step ),
            environments = mapOf( env.id to env )
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertEquals( 1, plan.steps.size )
        assertTrue( plan.issues.isEmpty() )
        assertEquals( 1, plan.requiredEnvironmentRefs.size )
        assertTrue( plan.requiredEnvironmentRefs.containsKey( env.id ) )

        val plannedStep = plan.steps[0]
        assertEquals( step.metadata.id, plannedStep.metadata.id )
        assertEquals( step.metadata.name, plannedStep.metadata.name )
    }

    // ── Environment Validation Tests ──────────────────────────────────────────

    @Test
    fun `plan detects missing environment references`()
    {
        // Arrange
        val missingEnvId = UUID.randomUUID()
        val step = createCommandStep( "step-missing-env", missingEnvId )
        val definition = createWorkflowDefinition(
            "Missing Environment Workflow",
            steps = listOf( step ),
            environments = emptyMap() // No environments defined
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertTrue( plan.issues.isNotEmpty() )

        val issue = plan.issues.find { it.code == "MISSING_ENVIRONMENT_DEFINITION" }
        assertNotNull( issue )
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertTrue( issue.message.contains( missingEnvId.toString() ) )
    }

    // ── Multiple Steps Tests ──────────────────────────────────────────────────

    @Test
    fun `plan preserves declaration order for independent steps`()
    {
        // Arrange
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env"
        )

        // Three independent steps - should maintain declaration order
        val step1 = createCommandStep( "step1", env.id )
        val step2 = createCommandStep( "step2", env.id )
        val step3 = createCommandStep( "step3", env.id )

        val definition = createWorkflowDefinition(
            "Declaration Order Workflow",
            steps = listOf( step1, step2, step3 ),
            environments = mapOf( env.id to env )
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertEquals( 3, plan.steps.size )
        assertTrue( plan.issues.isEmpty() )

        // Steps should maintain declaration order since they're independent
        val stepOrder = plan.steps.map { it.metadata.id }
        assertEquals( step1.metadata.id, stepOrder[0] )
        assertEquals( step2.metadata.id, stepOrder[1] )
        assertEquals( step3.metadata.id, stepOrder[2] )
    }

    @Test
    fun `plan handles unsupported task types`()
    {
        // Arrange
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env"
        )
        val step = createStep( "unsupported-step", env.id ) // Uses MockTaskDefinition
        val definition = createWorkflowDefinition(
            "Unsupported Task Workflow",
            steps = listOf( step ),
            environments = mapOf( env.id to env )
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertTrue( plan.steps.isEmpty() ) // Step compilation failed
        assertEquals( 1, plan.issues.size )

        val issue = plan.issues[0]
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertEquals( "UNSUPPORTED_TASK_TYPE", issue.code )
        assertEquals( step.metadata.id, issue.stepId )
    }

    @Test
    fun `plan is deterministic across multiple calls`()
    {
        // Arrange
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env"
        )
        val step1 = createCommandStep( "step1", env.id )
        val step2 = createCommandStep( "step2", env.id )
        val definition = createWorkflowDefinition(
            "Deterministic Workflow",
            steps = listOf( step1, step2 ),
            environments = mapOf( env.id to env )
        )

        // Act
        val plan1 = planner.plan( definition )
        val plan2 = planner.plan( definition )

        // Assert
        assertEquals( plan1.workflowName, plan2.workflowName )
        assertEquals( plan1.steps.size, plan2.steps.size )
        assertEquals( plan1.issues.size, plan2.issues.size )
        assertEquals( plan1.requiredEnvironmentRefs.size, plan2.requiredEnvironmentRefs.size )

        // Step order should be the same (deterministic)
        val order1 = plan1.steps.map { it.metadata.id }
        val order2 = plan2.steps.map { it.metadata.id }
        assertEquals( order1, order2 )

        // Environment refs should be the same (deterministic)
        assertEquals( plan1.requiredEnvironmentRefs, plan2.requiredEnvironmentRefs )
    }

    @Test
    fun `plan deduplicates required environment handles`()
    {
        // Arrange
        val env1 = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env1"
        )
        val env2 = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env2"
        )

        val step1 = createCommandStep( "step1", env1.id )
        val step2 = createCommandStep( "step2", env1.id ) // Same environment as step1
        val step3 = createCommandStep( "step3", env2.id )

        val definition = createWorkflowDefinition(
            "Environment Deduplication Workflow",
            steps = listOf( step1, step2, step3 ),
            environments = mapOf( env1.id to env1, env2.id to env2 )
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertEquals( 3, plan.steps.size )
        assertEquals( 2, plan.requiredEnvironmentRefs.size ) // Should be deduplicated
        assertTrue( plan.requiredEnvironmentRefs.containsKey( env1.id ) )
        assertTrue( plan.requiredEnvironmentRefs.containsKey( env2.id ) )
    }

    @Test
    fun `plan produces deterministic ordering with multiple environments`()
    {
        // Arrange - Use multiple environments to test sorting
        val env1 = CondaEnvironmentDefinition( id = UUID.randomUUID(), name = "env1" )
        val env2 = CondaEnvironmentDefinition( id = UUID.randomUUID(), name = "env2" )
        val env3 = CondaEnvironmentDefinition( id = UUID.randomUUID(), name = "env3" )

        val step1 = createCommandStep( "step1", env2.id ) // Use env2 first
        val step2 = createCommandStep( "step2", env1.id ) // Then env1
        val step3 = createCommandStep( "step3", env3.id ) // Then env3

        val definition = createWorkflowDefinition(
            "Environment Sorting Workflow",
            steps = listOf( step1, step2, step3 ),
            environments = mapOf( env1.id to env1, env2.id to env2, env3.id to env3 )
        )

        // Act - Run multiple times
        val plan1 = planner.plan( definition )
        val plan2 = planner.plan( definition )
        val plan3 = planner.plan( definition )

        // Assert
        assertEquals( 3, plan1.requiredEnvironmentRefs.size )

        // Environment refs should be deterministic across runs
        assertEquals( plan1.requiredEnvironmentRefs, plan2.requiredEnvironmentRefs )
        assertEquals( plan2.requiredEnvironmentRefs, plan3.requiredEnvironmentRefs )

        // Should contain all three environments
        assertTrue( plan1.requiredEnvironmentRefs.containsKey( env1.id ) )
        assertTrue( plan1.requiredEnvironmentRefs.containsKey( env2.id ) )
        assertTrue( plan1.requiredEnvironmentRefs.containsKey( env3.id ) )
    }

    @Test
    fun `plan collects multiple issues from different sources`()
    {
        // Arrange
        val validEnvId = UUID.randomUUID()
        val missingEnvId = UUID.randomUUID()

        // Step with missing environment
        val stepMissingEnv = createCommandStep( "step-missing-env", missingEnvId )

        // Step with unsupported task
        val stepUnsupportedTask = createStep( "step-unsupported", validEnvId )

        // Step with valid environment and command task
        val validStep = createCommandStep( "valid-step", validEnvId )

        val definition = createWorkflowDefinition(
            "Multiple Issues Workflow",
            steps = listOf( stepMissingEnv, stepUnsupportedTask, validStep ),
            environments = mapOf(
                validEnvId to MockEnvironmentDefinition( id = validEnvId, name = "valid-env" )
            )
        )

        // Act
        val plan = planner.plan( definition )

        // Assert
        assertNotNull( plan )
        assertTrue( plan.issues.size >= 2 ) // At least missing env + unsupported task

        assertTrue( plan.issues.any { it.code == "MISSING_ENVIRONMENT_DEFINITION" } )
        assertTrue( plan.issues.any { it.code == "UNSUPPORTED_TASK_TYPE" } )
    }
}
