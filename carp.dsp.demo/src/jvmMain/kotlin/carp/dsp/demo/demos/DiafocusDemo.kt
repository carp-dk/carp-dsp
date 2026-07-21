package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.io.path.*
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * DiaFocus Blood Glucose Monitoring + Daily Steps Analysis Demo
 *
 * Demonstrates a complete DSP workflow that:
 * 1. Loads blood glucose and steps data from a JSON mock file
 * 2. Analyses glucose metrics and step trends
 * 3. Produces a comprehensive health summary
 *
 * The workflow runs end-to-end using the DSP engine with:
 * - Workflow descriptor from YAML (resources/workflows/diafocus-bgm-steps.yaml)
 * - Input data files (resources/data/diafocus_mock.json)
 * - Python analysis scripts
 * - System Python environment (no conda/pixi setup)
 */
class DiafocusDemo {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            run()
        }

        fun run() {
            executeDemo()
        }

        @OptIn(ExperimentalPathApi::class)
        private fun executeDemo() {
            // Use persistent demo_results directory instead of temp dir
            val demoResultsDir = getDemoResultsDirectory()

            // Use static run ID for consistent results
            val runId = UUID.parse("00000000-0000-0000-0000-000000000001")

            try {
                println("=" * 70)
                println("DiaFocus Blood Glucose & Steps Analysis Demo")
                println("=" * 70)
                println()

                // 0. Clean up existing results before running
                if (demoResultsDir.exists()) {
                    println("Cleaning up previous results...")
                    demoResultsDir.deleteRecursively()
                }
                demoResultsDir.createDirectories()

                // 1. Load YAML workflow from resources
                val workflowYaml = loadWorkflowYaml()
                val descriptor = WorkflowYamlCodec().decodeOrThrow(workflowYaml)
                println("Workflow loaded: ${descriptor.metadata.name}")

                // 2. Copy data files to workspace
                setupWorkspaceFiles(demoResultsDir)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Import workflow descriptor and generate execution plan
                val definition = WorkflowDescriptorImporter().import(descriptor)
                val planner = DefaultExecutionPlanner()
                val plan = planner.plan(definition)
                plan.validate()
                println("Execution plan generated (${plan.steps.size} steps)")

                // 4. Set up executor infrastructure
                val artefactStore = FileSystemArtefactStore(demoResultsDir.resolve("artifacts"))
                val workspaceManager = DefaultWorkspaceManager(demoResultsDir)
                val executor = DefaultPlanExecutor(
                    workspaceManager = workspaceManager,
                    artefactStore = artefactStore
                )

                // 5. Execute workflow
                println()
                println("Executing workflow...")
                println("-" * 70)
                val report = executor.execute(plan, runId)
                println("-" * 70)

                // 6. Verify execution succeeded
                if (report.status.toString() != "SUCCEEDED") {
                    println("Workflow execution failed: ${report.status}")
                    report.issues.forEach { issue ->
                        println("   - ${issue.message}")
                    }
                    return
                }
                println("Workflow execution succeeded")
                println()

                // 7. Read and display results from summary.json
                // The workflow creates a directory structure: <workflowName>/run_<runId>/steps/<stepIndex>_<stepName>/outputs/
                val workflowName = "diafocus_blood_glucose__steps_analysis"
                val summaryFile = demoResultsDir.resolve(
                    "$workflowName/run_${runId}/steps/03_analyse_bgm_and_steps/outputs/summary-json.json"
                )
                if (summaryFile.exists()) {
                    printAnalysisSummary(summaryFile.readText())
                } else {
                    println("Summary output not found at: $summaryFile")
                }

                println()
                println("=" * 70)
                println("Demo completed successfully!")
                println("Results saved to: $demoResultsDir")
                println("=" * 70)

            } catch (e: Exception) {
                println("Error during demo execution: ${e.message}")
                e.printStackTrace()
            }
        }

        private fun loadWorkflowYaml(): String = DemoIo.loadResource("workflows/diafocus-bgm-steps.yaml")

        @OptIn(ExperimentalPathApi::class)
        private fun getDemoResultsDirectory(): Path = DemoIo.demoResultsDir("diafocus").toPath()

        @OptIn(ExperimentalPathApi::class)
        private fun setupWorkspaceFiles(tmpDir: Path) {
            // Copy workflow YAML
            val workflowsDir = tmpDir.resolve("resources/workflows")
            workflowsDir.createDirectories()
            copyResourceFile("workflows/diafocus-bgm-steps.yaml", workflowsDir.resolve("diafocus-bgm-steps.yaml"))

            // Copy data files
            val dataDir = tmpDir.resolve("resources/data")
            dataDir.createDirectories()
            copyResourceFile("data/diafocus_mock.json", dataDir.resolve("diafocus_mock.json"))

            // Copy scripts
            val scriptsDir = tmpDir.resolve("resources/scripts")
            scriptsDir.createDirectories()
            copyResourceFile("scripts/load_bgm.py", scriptsDir.resolve("load_bgm.py"))
            copyResourceFile("scripts/load_steps.py", scriptsDir.resolve("load_steps.py"))
            copyResourceFile("scripts/bgm_steps_analysis.py", scriptsDir.resolve("bgm_steps_analysis.py"))
        }

        private fun copyResourceFile(resourcePath: String, targetPath: Path) =
            DemoIo.copyResource(resourcePath, targetPath)

        private fun printAnalysisSummary(summaryJson: String) {
            try {
                val gson = Gson()
                val json = gson.fromJson(summaryJson, JsonObject::class.java)

                println("ANALYSIS RESULTS")
                println("-" * 70)

                val bgmMetrics = json.getAsJsonObject("blood_glucose_metrics")
                if (bgmMetrics != null) {
                    println()
                    println("Blood Glucose Metrics:")
                    println("  Time in Range (3.9-10 mmol/L):  ${bgmMetrics.get("pct_in_range")}%")
                    println("  Below Range (<3.9 mmol/L):     ${bgmMetrics.get("pct_below")}%")
                    println("  Above Range (>10 mmol/L):      ${bgmMetrics.get("pct_above")}%")
                    println("  Mean BGM:                       ${bgmMetrics.get("mean_bgm")} mmol/L")
                    println("  Std Dev:                        ${bgmMetrics.get("std_bgm")} mmol/L")
                    println("  Total Readings:                 ${bgmMetrics.get("total_readings")}")
                }

                val stepsMetrics = json.getAsJsonObject("steps_metrics")
                if (stepsMetrics != null) {
                    println()
                    println("Steps Metrics:")
                    println("  Mean Daily Steps:               ${stepsMetrics.get("mean_daily_steps")}")
                    println("  Median Daily Steps:             ${stepsMetrics.get("median_daily_steps")}")
                    println("  Min Daily Steps:                ${stepsMetrics.get("min_daily_steps")}")
                    println("  Max Daily Steps:                ${stepsMetrics.get("max_daily_steps")}")
                }

                val stepTrend = json["step_trend"]?.asString
                if (stepTrend != null) {
                    println()
                    println("Step Trend:")
                    println("  Trend:                          $stepTrend")
                }

                println()
                println("-" * 70)
            } catch (e: Exception) {
                println("Could not parse summary results: ${e.message}")
                println("Raw summary:")
                println(summaryJson)
            }
        }

        private operator fun String.times(count: Int): String = repeat(count)
    }
}

