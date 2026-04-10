package carp.dsp.core.application.plan

import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.workflow.*
import dk.cachet.carp.common.application.UUID

// ── ExecutionPlan Helpers ──────────────────────────────────────────────────────

fun createTestExecutionPlan(
    workflowId: String = "test-workflow",
    planId: String = UUID.randomUUID().toString(),
    stepCount: Int = 2,
    environmentCount: Int = 1,
    issues: List<PlanIssue> = emptyList()
): ExecutionPlan {
    val steps = (1..stepCount).map { i ->
        PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "Step $i",
            ),
            process = CommandSpec("python", listOf(ExpandedArg.Literal("script.py"))),
            bindings = createBindingsWithInputsOutputs(),
            environmentRef = UUID.randomUUID()
        )
    }

    val environments = (1..environmentCount).associate { i ->
        UUID.randomUUID() to CondaEnvironmentRef(
            id = "env-$i",
            name = "test-env-$i",
            dependencies = listOf("numpy"),
            channels = listOf("conda-forge"),
            pythonVersion = "3.11"
        )
    }

    return ExecutionPlan(
        workflowName = workflowId,
        planId = planId,
        steps = steps,
        issues = issues,
        requiredEnvironmentRefs = environments
    )
}

// ── WorkflowDefinition Helpers ─────────────────────────────────────────────────

fun createTestWorkflowDefinition(
    workflowName: String = "test-workflow",
    stepCount: Int = 2,
    environmentCount: Int = 1
): WorkflowDefinition {
    val steps = (1..stepCount).map { i ->
        Step(
            metadata = StepMetadata(
                name = "Step $i",
                id = UUID.randomUUID(),
                description = "Test step $i",
                version = Version(1)
            ),
            inputs = emptyList(),
            outputs = listOf(
                OutputDataSpec(
                    id = UUID.randomUUID(),
                    name = "output-$i",
                    location = FileLocation(
                        path = "/data/output-$i.csv",
                        format = FileFormat.CSV,
                        metadata = mapOf("encoding" to "UTF-8")
                    ),
                )
            ),
            task = CommandTaskDefinition(
                id = UUID.randomUUID(),
                name = "task-$i",
                description = "Test task",
                executable = "python",
                args = listOf(Literal("script.py"))
            ),
            environmentId = UUID.randomUUID()
        )
    }

    val environments = (1..environmentCount).associate { i ->
        UUID.randomUUID() to CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env-$i",
            channels = listOf("conda-forge"),
            pythonVersion = "3.11",
            dependencies = listOf("numpy", "scipy")
        )
    }

    val workflow = Workflow(
        metadata = WorkflowMetadata(
            name = workflowName,
            id = UUID.randomUUID(),
            description = "Test workflow",
            version = Version(1)
        )
    )
    steps.forEach { workflow.addComponent(it) }

    return WorkflowDefinition(
        workflow = workflow,
        environments = environments
    )
}

// ── PlanIssue Helpers ──────────────────────────────────────────────────────────

// ── Mock Objects ───────────────────────────────────────────────────────────────

class MockPlanHasher(val hashValue: String = "mock-hash-12345") : PlanHasher {
    override fun hash(plan: ExecutionPlan): String = hashValue
}

class DeterministicPlanHasher : PlanHasher {
    override fun hash(plan: ExecutionPlan): String {
        // Simple deterministic hash for testing
        return plan.planId.hashCode().toString().padStart(64, '0').take(64)
    }
}

// ── Binding Helpers ────────────────────────────────────────────────────────────
// Create a single ResolvedInput. All parameters are customizable with sensible defaults.
fun createResolvedInput(
    id: UUID = UUID.randomUUID(),
    name: String = "input-data",
    description: String = "Test input data",
    // Source-specific defaults
    path: String = "/data/input.csv",
    format: FileFormat = FileFormat.CSV,
    metadata: Map<String, String> = mapOf("encoding" to "UTF-8"),
): ResolvedInput {
    val spec = InputDataSpec(
        id = id,
        name = name,
        description = description,
        location = FileLocation(
            path = path,
            format = format,
            metadata = metadata
        )
    )


    return ResolvedInput(
        spec,
        location = FileLocation(
        path = path,
        format = format,
        metadata = metadata
    )
    )
}

fun createResolvedOutput(
    id: UUID = UUID.randomUUID(),
    name: String = "output-data",
    description: String = "Test output data",
    format: FileFormat? = null,
    path: String? = null,
    resolvedPath: String? = null,
): ResolvedOutput {
    // Use path if provided, otherwise try resolvedPath, otherwise use default
    val actualPath = path ?: resolvedPath ?: "/data/output.json"

    val fileLocation = FileLocation(
        path = actualPath,
        format = format ?: FileFormat.CSV
    )

    val spec = OutputDataSpec(
        id = id,
        name = name,
        description = description,
        location = fileLocation
    )

    return ResolvedOutput(spec, location = fileLocation)
}

/**
 * Overload for minimal calls: createResolvedOutput(id, "text/plain")
 */
fun createResolvedOutput(id: UUID, formatMimeType: String): ResolvedOutput {
    val format = try {
        FileFormat.entries.first { it.mimeType == formatMimeType }
    } catch (_: NoSuchElementException) {
        FileFormat.CSV
    }
    return createResolvedOutput(id, format = format)
}


// Helper that composes a ResolvedInput and ResolvedOutput into ResolvedBindings.
fun createBindingsWithInputsOutputs(
    inputId: UUID = UUID.randomUUID(),
    outputId: UUID = UUID.randomUUID(),
): ResolvedBindings {
    val resolvedInput = createResolvedInput(id = inputId, path = "/data/input.csv")
    val resolvedOutput = createResolvedOutput(id = outputId, path = "/data/output.json")

    return ResolvedBindings(
        inputs = mapOf(UUID.randomUUID() to resolvedInput),
        outputs = mapOf(UUID.randomUUID() to resolvedOutput)
    )
}
