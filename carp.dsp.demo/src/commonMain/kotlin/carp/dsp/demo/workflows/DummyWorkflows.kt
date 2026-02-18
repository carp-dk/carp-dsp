package carp.dsp.demo.workflows

import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.analytics.application.authoring.WorkflowDefinitionBuilder
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID

/**
 * Factory for creating dummy workflows for testing and demonstration purposes.
 */
object DummyWorkflows {

    /**
     * Mock implementation of [EnvironmentDefinition] for testing.
     * Provides a minimal but valid environment definition.
     */
    private data class MockEnvironmentDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val dependencies: List<String> = emptyList(),
        override val environmentVariables: Map<String, String> = emptyMap()
    ) : EnvironmentDefinition

    /**
     * Creates a valid workflow with minimal requirements for each step.
     *
     * @param numSteps Number of steps to include (1-10). Defaults to 2.
     *                 Values exceeding 10 will log a warning and use 10 instead.
     * @return A [WorkflowDefinition] with the specified number of steps
     */
    fun validWorkflowWithMinRequirements(numSteps: Int = 2): WorkflowDefinition {
        val steps = validateStepCount(numSteps)

        val metadata = WorkflowMetadata(
            name = "Minimal Workflow",
            description = "A valid workflow with minimal requirements",
            id = UUID.randomUUID()
        )

        val builder = WorkflowDefinitionBuilder(metadata)

        // Create a minimal environment
        val minimalEnv = MockEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "minimal_env",
            dependencies = emptyList(),
            environmentVariables = emptyMap()
        )

        builder.addEnvironment(minimalEnv)

        repeat(steps) { index ->
            val stepName = "step_${index + 1}"
            val step = Step(
                metadata = StepMetadata(
                    id = UUID.randomUUID(),
                    name = stepName,
                    description = "Minimal step $stepName"
                ),
                environmentId = minimalEnv.id,
                task = createMinimalTask()
            )
            builder.addComponent(step)
        }

        return builder.build()
    }

    /**
     * Creates a valid workflow with all fields populated.
     *
     * Includes complete step definitions with inputs, outputs, environments,
     * and registered environments in the provided DataRegistry.
     *
     * @param numSteps Number of steps to include (1-10). Defaults to 2.
     *                 Values exceeding 10 will log a warning and use 10 instead.
     * @return A [WorkflowDefinition] with fully populated steps and environments
     */
    fun validWorkflowWithAllFields(
        numSteps: Int = 2,
    ): WorkflowDefinition {
        val steps = validateStepCount(numSteps)

        val metadata = WorkflowMetadata(
            name = "Complete Workflow",
            description = "A valid workflow with all fields populated",
            id = UUID.randomUUID()
        )

        val builder = WorkflowDefinitionBuilder(metadata)

        // Create and add environments
        val condaEnv = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "analysis_env",
            dependencies = listOf("pandas", "numpy", "scipy"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )

        val pixiEnv = PixiEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "data_env",
            dependencies = listOf("polars", "duckdb"),
            pythonVersion = "3.12"
        )

        builder.addEnvironment(condaEnv)
        builder.addEnvironment(pixiEnv)


        // Create steps with full configuration
        repeat(steps) { index ->
            val stepName = "step_${index + 1}"
            val step = Step(
                metadata = StepMetadata(
                    id = UUID.randomUUID(),
                    name = stepName,
                    description = "Complete step with all fields: $stepName"
                ),
                inputs = listOf(
                    InputDataSpec(
                        identifier = "input_$index",
                        name = "Input Data $index",
                        source = InMemorySource(registryKey = "analysis_env")
                    )
                ),
                outputs = listOf(
                    OutputDataSpec(
                        identifier = "output_$index",
                        name = "Output Data $index",
                        destination = RegistryDestination(key = "output_$index")
                    )
                ),
                environmentId = if (index % 2 == 0) condaEnv.id else pixiEnv.id,
                task = createCompleteTask(stepName)
            )
            builder.addComponent(step)
        }

        return builder.build()
    }

    /**
     * Creates an invalid workflow with specified type of violation.
     *
     * Violation types match [dk.cachet.carp.analytics.domain.validation.WorkflowValidator] checks:
     * - **DUPLICATE_STEP_IDS**: Two or more steps with the same ID
     * - **MISSING_DEPENDENCY_REFERENCE**: Step depends on non-existent step
     * - **CIRCULAR_DEPENDENCY**: Steps form a circular dependency chain
     * - **EMPTY_WORKFLOW**: Workflow contains no steps
     *
     * @param violationType Type of violation to introduce. Defaults to DUPLICATE_STEP_IDS.
     * @return A [WorkflowDefinition] with the specified violation
     */
    fun invalidWorkflow(
        violationType: ViolationType = ViolationType.DUPLICATE_STEP_IDS
    ): WorkflowDefinition {
        val metadata = WorkflowMetadata(
            name = "Invalid Workflow",
            description = "A workflow with deliberate violations for testing validation",
            id = UUID.randomUUID()
        )

        val builder = WorkflowDefinitionBuilder(metadata)

        // Create default environment for most violations
        val defaultEnv = MockEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "default_env"
        )
        builder.addEnvironment(defaultEnv)

        return when (violationType) {
            ViolationType.DUPLICATE_STEP_IDS -> {
                // Two steps with the same ID - violates uniqueness constraint
                val duplicateId = UUID.randomUUID()
                val step1 = Step(
                    metadata = StepMetadata(
                        id = duplicateId,
                        name = "step_duplicate",
                        description = "First step with duplicated ID"
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                val step2 = Step(
                    metadata = StepMetadata(
                        id = duplicateId, // Same ID as step1 - INVALID
                        name = "step_duplicate_copy",
                        description = "Second step with same ID as first"
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.addComponent(step2)
                builder.build()
            }

            ViolationType.MISSING_DEPENDENCY_REFERENCE -> {
                // Step references a non-existent step in dependencies
                val step1 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_with_bad_ref",
                        description = "Step referencing non-existent dependency"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "bad_input",
                            name = "Bad Input",
                            source = InMemorySource(registryKey = "non_existent_step") // References non-existent step
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.build()
            }

            ViolationType.CIRCULAR_DEPENDENCY -> {
                // Self-referencing step (simplest cycle)
                val step1 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_self_cycle",
                        description = "Step that depends on itself"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "self_ref",
                            name = "Self Reference",
                            source = InMemorySource(
                                registryKey = "step_self_cycle"
                            ) // Depends on itself - INVALID cycle
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.build()
            }

            ViolationType.TWO_STEP_CYCLE -> {
                // Two steps that depend on each other
                val step1 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_cycle_a",
                        description = "First step in circular dependency"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "from_b",
                            name = "Data from Step B",
                            source = InMemorySource(registryKey = "step_cycle_b")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )

                val step2 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_cycle_b",
                        description = "Second step in circular dependency"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "from_a",
                            name = "Data from Step A",
                            source = InMemorySource(registryKey = "step_cycle_a")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.addComponent(step2)
                builder.build()
            }

            ViolationType.THREE_STEP_CYCLE -> {
                // Three steps forming a cycle: s1 -> s2 -> s3 -> s1
                val step1 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_s1",
                        description = "Step 1 of 3-step cycle"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "from_s3",
                            name = "Data from S3",
                            source = InMemorySource(registryKey = "step_s3")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )

                val step2 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_s2",
                        description = "Step 2 of 3-step cycle"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "from_s1",
                            name = "Data from S1",
                            source = InMemorySource(registryKey = "step_s1")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )

                val step3 = Step(
                    metadata = StepMetadata(
                        id = UUID.randomUUID(),
                        name = "step_s3",
                        description = "Step 3 of 3-step cycle"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "from_s2",
                            name = "Data from S2",
                            source = InMemorySource(registryKey = "step_s2")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.addComponent(step2)
                builder.addComponent(step3)
                builder.build()
            }

            ViolationType.EMPTY_WORKFLOW -> {
                // Workflow with no steps at all
                builder.build()
            }

            ViolationType.MULTIPLE_VIOLATIONS -> {
                // Workflow with multiple violations: duplicate IDs + missing references
                val duplicateId = UUID.randomUUID()

                val step1 = Step(
                    metadata = StepMetadata(
                        id = duplicateId,
                        name = "step_multi_1",
                        description = "First step with multiple violations"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "missing_input",
                            name = "Missing Input",
                            source = InMemorySource(registryKey = "non_existent")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )

                val step2 = Step(
                    metadata = StepMetadata(
                        id = duplicateId, // Duplicate ID
                        name = "step_multi_2",
                        description = "Second step also with duplicate ID"
                    ),
                    inputs = listOf(
                        InputDataSpec(
                            identifier = "another_missing",
                            name = "Another Missing Input",
                            source = InMemorySource(registryKey = "also_non_existent")
                        )
                    ),
                    environmentId = defaultEnv.id,
                    task = createMinimalTask()
                )
                builder.addComponent(step1)
                builder.addComponent(step2)
                builder.build()
            }
        }
    }

    // ============ Helper Functions ============

    private fun validateStepCount(numSteps: Int): Int {
        return when {
            numSteps < 1 -> {
                println("⚠ Warning: Number of steps must be at least 1, using default 2")
                2
            }
            numSteps > 10 -> {
                println("⚠ Warning: Maximum 10 steps allowed, capping at 10")
                10
            }
            else -> numSteps
        }
    }

    private fun createMinimalTask(): TaskDefinition {
        return object : TaskDefinition {
            override val id: UUID = UUID.randomUUID()
            override val name: String = "minimal_task"
            override val description: String = "Minimal task definition"
        }
    }

    private fun createCompleteTask(stepName: String): TaskDefinition {
        return object : TaskDefinition {
            override val id: UUID = UUID.randomUUID()
            override val name: String = "task_$stepName"
            override val description: String = "Complete task definition for $stepName with full details"
        }
    }

    /**
     * Enumeration of workflow violation types for testing validation.
     *
     * Each violation type corresponds to checks performed by [dk.cachet.carp.analytics.domain.validation.WorkflowValidator]:
     */
    enum class ViolationType {
        /** Two or more steps share the same ID - violates uniqueness constraint */
        DUPLICATE_STEP_IDS,

        /** A step references a non-existent step via input data - violates reference validity */
        MISSING_DEPENDENCY_REFERENCE,

        /** A step depends on itself - violates acyclicity constraint */
        CIRCULAR_DEPENDENCY,

        /** Two steps depend on each other - violates acyclicity constraint */
        TWO_STEP_CYCLE,

        /** Three steps form a circular chain - violates acyclicity constraint */
        THREE_STEP_CYCLE,

        /** Workflow contains no steps */
        EMPTY_WORKFLOW,

        /** Workflow with both duplicate IDs and missing references */
        MULTIPLE_VIOLATIONS
    }
}
