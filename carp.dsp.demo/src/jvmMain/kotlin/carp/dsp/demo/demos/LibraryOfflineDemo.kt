package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.runWorkflow
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Offline library demo: the activity-summary pipeline built entirely from
 * certified library steps, sourcing synthetic data instead of fetching it.
 *
 * Same `uses:` path as [LibraryReuseDemo] - resolution, content-hash pinning,
 * staging the library implementations into the run - but with no network access,
 * so it is the one to run in CI or where a download would be the least reliable
 * part of the demo. Still needs the pixi environments the steps declare.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run library-offline"
 */
object LibraryOfflineDemo {
    fun run() {
        val demoDir = DemoIo.demoResultsDir("library_offline").toPath()
        demoDir.createDirectories()

        val workflowFile = demoDir.resolve("wf-activity-summary-offline.yaml")
        DemoIo.copyResource("workflows/wf-activity-summary-offline.yaml", workflowFile)

        val workspace = demoDir.resolve("run")

        println("Offline library demo - activity summary from `uses:` steps, synthetic source")
        println("Workflow: ${workflowFile.absolutePathString()}")
        println()

        runWorkflow(workflowFile.absolutePathString(), workspace.absolutePathString())
    }
}
