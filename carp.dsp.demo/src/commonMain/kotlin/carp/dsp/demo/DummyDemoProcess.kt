package carp.dsp.demo

import carp.dsp.core.domain.execution.ExecutionFactory
import carp.dsp.core.domain.execution.SequentialExecutionStrategy
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.domain.data.ICarpTabularData
import dk.cachet.carp.analytics.domain.process.AnalysisProcess
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import kotlinx.coroutines.runBlocking

class DummyDemoProcess(private val msg: String) : AnalysisProcess {
    override fun process(input: ICarpTabularData): ICarpTabularData? {
        println("Demo processing input: $msg")
        return null
    }

    override val name: String = "Demo Process"
    override val description: String = "A demo analysis process that does nothing."
}

fun buildWorkflowStep(): Workflow {
    val metadata = WorkflowMetadata(
        name = "Demo Workflow",
        description = "A demonstration workflow for DSP.",
        id = UUID.randomUUID()
    )

    val workflow = Workflow(metadata)

    val demoStepMetadata = StepMetadata(
        name = "Demo Step",
        description = "A demonstration step that uses DemoProcess.",

    )

    workflow.addComponent(
        Step(
        metadata = demoStepMetadata,
        process = DummyDemoProcess("Demo Step 1")
    )
    )

    workflow.addComponent(
        Step(
        metadata = demoStepMetadata,
        process = DummyDemoProcess("Demo Step 2")
    )
    )

    return workflow
}

fun main() = runBlocking {
    // 1. Build a demo workflow
    val workflow = buildWorkflowStep()
    println("Built workflow: $workflow")

    // 2. Setup environment
    val dataRegistry = DataRegistry()
    val executionStrategy = SequentialExecutionStrategy(dataRegistry)

    // 3. Execute the workflow
    executionStrategy.execute(workflow, ExecutionFactory())
}

