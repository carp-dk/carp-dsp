package carp.dsp.core.application.plan

import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
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
            stepId = UUID.randomUUID(),
            name = "Step $i",
            process = CommandSpec("python", listOf(ExpandedArg.Literal("script.py"))),
            bindings = ResolvedBindings(
                inputs = mapOf(),
                outputs = mapOf(UUID.randomUUID() to DataRef(UUID.randomUUID(), "csv"))
            ),
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
        workflowId = workflowId,
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
                    destination = FileDestination("output-$i.csv", format = FileFormat.CSV),
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

fun createTestPlanWithIssues(
    errors: Int = 0,
    warnings: Int = 0,
    infos: Int = 0
): ExecutionPlan {
    val issues = mutableListOf<PlanIssue>()

    repeat(errors) { i ->
        issues.add(
            PlanIssue(
                severity = PlanIssueSeverity.ERROR,
                code = "ERROR_$i",
                message = "Error $i",
                stepId = null
            )
        )
    }

    repeat(warnings) { i ->
        issues.add(
            PlanIssue(
                severity = PlanIssueSeverity.WARNING,
                code = "WARNING_$i",
                message = "Warning $i",
                stepId = null
            )
        )
    }

    repeat(infos) { i ->
        issues.add(
            PlanIssue(
                severity = PlanIssueSeverity.INFO,
                code = "INFO_$i",
                message = "Info $i",
                stepId = null
            )
        )
    }

    return createTestExecutionPlan(issues = issues)
}

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
