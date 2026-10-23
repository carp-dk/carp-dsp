package carp.dsp.core.application.run

import carp.dsp.core.application.authoring.resolve.StepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [WorkflowExecutor.prepare] lints the workflow and folds the result into the plan. */
class WorkflowExecutorLintTest
{
    private val root: Path = Files.createTempDirectory( "executor-lint" )
    private val executor = WorkflowExecutor.filesystem(
        stepLibrary = StepLibrary { _, _ -> null },
        workspaceRoot = root.resolve( "runs" ),
    )

    private fun workflow( environmentId: String ): WorkflowSource
    {
        root.resolve( "workflow.yaml" ).writeText(
            """
            |schemaVersion: "1.0"
            |metadata:
            |  id: "lint-test"
            |  name: "lint-test"
            |  version: "1.0"
            |environments:
            |  env-system:
            |    name: "system"
            |    kind: "system"
            |    spec: {}
            |steps:
            |  - id: "echo"
            |    metadata:
            |      name: "Echo"
            |      version: "1.0"
            |    environmentId: "$environmentId"
            |    dependsOn: []
            |    task:
            |      type: "command"
            |      id: "task-echo"
            |      name: "echo"
            |      executable: "echo"
            |      args: ["hello"]
            """.trimMargin()
        )
        return WorkflowSource( root, "workflow.yaml" )
    }

    @Test
    fun `an undefined environment is reported by its key and blocks the run`()
    {
        val prepared = executor.prepare( workflow( environmentId = "env-missing" ) )

        val lintError = prepared.plan.issues.single { it.code == "WORKFLOW_MISSING_ENVIRONMENT" }
        assertEquals( PlanIssueSeverity.ERROR, lintError.severity )
        assertTrue( lintError.message.contains( "'echo'" ) )
        assertTrue( lintError.message.contains( "'env-missing'" ) )
        assertFalse( prepared.plan.isRunnable() )
        assertEquals( ExecutionStatus.FAILED, executor.run( prepared ).status )
    }

    @Test
    fun `a clean workflow gets no lint errors`()
    {
        val prepared = executor.prepare( workflow( environmentId = "env-system" ) )

        assertTrue( prepared.plan.issues.none { it.severity == PlanIssueSeverity.ERROR } )
    }
}
