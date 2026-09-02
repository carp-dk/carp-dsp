package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.runWorkflow
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Library reuse demo: runs the activity-summary pipeline built entirely from
 * certified library steps referenced with `uses:` (v2 of wf-activity-summary).
 *
 * Exercises the whole library path end to end: fetch-zenodo (twice, one with an
 * `args:` override for the heart-rate member), join-tables (configured per use via
 * `args:`), select-columns per signal, the two sensing cleaning steps,
 * daily-features, summarise and visualise - each
 * resolved from the shared library and pinned in steps.lock.
 *
 * Needs network access (the Fitbit dataset is fetched from Zenodo) and the pixi
 * environments the steps declare.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run library-reuse"
 */
object LibraryReuseDemo {
    fun run() {
        val demoDir = DemoIo.demoResultsDir("library_reuse").toPath()
        demoDir.createDirectories()

        val workflowFile = demoDir.resolve("wf-activity-summary-v2.yaml")
        DemoIo.copyResource("workflows/wf-activity-summary-v2.yaml", workflowFile)

        val workspace = demoDir.resolve("run")

        println("Library reuse demo - activity summary from `uses:` library steps")
        println("Workflow: ${workflowFile.absolutePathString()}")
        println()

        runWorkflow(workflowFile.absolutePathString(), workspace.absolutePathString())
    }
}
