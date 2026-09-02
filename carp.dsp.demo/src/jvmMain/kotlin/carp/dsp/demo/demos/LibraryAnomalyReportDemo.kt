package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.runWorkflow
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Anomaly-report demo: the activity-summary pipeline with a detector on the end,
 * run end to end (v2 of wf-anomaly-report).
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run library-anomaly"
 */
object LibraryAnomalyReportDemo {
    fun run() {
        val demoDir = DemoIo.demoResultsDir("library_anomaly_report").toPath()
        demoDir.createDirectories()

        val workflowFile = demoDir.resolve("wf-anomaly-report-v2.yaml")
        DemoIo.copyResource("workflows/wf-anomaly-report-v2.yaml", workflowFile)

        val workspace = demoDir.resolve("run")

        println("Anomaly report demo - eleven library steps via `uses:`, two signals joined daily")
        println("Workflow: ${workflowFile.absolutePathString()}")
        println()

        runWorkflow(workflowFile.absolutePathString(), workspace.absolutePathString())
    }
}
