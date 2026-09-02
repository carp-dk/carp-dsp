package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.runWorkflow
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Library step demo: runs a workflow that references the vendored library step
 * `sensing.heartrate.clean` via `uses:` rather than inlining it.
 *
 * Shows the full library path end to end: resolution fills the task, environment
 * and ports from the library and pins them in `steps.lock`; the library step's
 * `clean.py` is staged into the run workspace; the declared input CSV is
 * provisioned beside it; then the step executes.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run library-clean"
 */
object LibraryCleanDemo {
    fun run() {
        val demoDir = DemoIo.demoResultsDir("library_clean").toPath()
        demoDir.createDirectories()

        // Materialize the workflow and its input CSV so the file input resolves
        // beside the workflow, exactly as an authored workflow on disk would.
        val workflowFile = demoDir.resolve("hr-clean-library.yaml")
        DemoIo.copyResource("workflows/hr-clean-library.yaml", workflowFile)
        DemoIo.copyResource("workflows/raw_heart_rate.csv", demoDir.resolve("raw_heart_rate.csv"))

        val workspace = demoDir.resolve("run")

        println("Library step demo - references sensing.heartrate.clean via `uses:`")
        println("Workflow: ${workflowFile.absolutePathString()}")
        println()

        runWorkflow(workflowFile.absolutePathString(), workspace.absolutePathString())
    }
}
