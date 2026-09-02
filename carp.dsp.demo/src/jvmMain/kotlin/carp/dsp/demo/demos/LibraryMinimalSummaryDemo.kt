package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.runWorkflow
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Minimal-summary demo: the shortest recomposition of the shared library, run
 * end to end (v2 of wf-minimal-summary).
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run library-minimal"
 */
object LibraryMinimalSummaryDemo {
    fun run() {
        val demoDir = DemoIo.demoResultsDir("library_minimal_summary").toPath()
        demoDir.createDirectories()

        val workflowFile = demoDir.resolve("wf-minimal-summary-v2.yaml")
        DemoIo.copyResource("workflows/wf-minimal-summary-v2.yaml", workflowFile)

        val workspace = demoDir.resolve("run")

        println("Minimal summary demo - four library steps, one signal, via `uses:`")
        println("Workflow: ${workflowFile.absolutePathString()}")
        println()

        runWorkflow(workflowFile.absolutePathString(), workspace.absolutePathString())
    }
}
