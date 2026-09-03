package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.DemoRun
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.steps.ClasspathStepLibrary
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

        private fun executeDemo() {
            // Use persistent demo_results directory instead of temp dir
            val demoResultsDir = getDemoResultsDirectory()

            // Use static run ID for consistent results
            val runId = UUID.parse("00000000-0000-0000-0000-000000000001")

            try {
                DemoRun.banner("DiaFocus Blood Glucose & Steps Analysis Demo")
                DemoRun.freshResultsDir(demoResultsDir)

                // 1. Load the workflow. It is written into the results directory,
                // which is what its steps.lock and relative inputs resolve against.
                val loaded = DemoRun.loadWorkflow("workflows/diafocus-bgm-steps.yaml", demoResultsDir)

                // 2. Lay out the files the run needs. Task script paths are relative
                // to the execution root (a command's working directory), while a
                // declared file input is resolved relative to the workflow file, so
                // the two go in different places.
                val workflowName = "diafocus_blood_glucose__steps_analysis"
                val executionRoot = demoResultsDir.resolve("$workflowName/run_$runId")
                executionRoot.createDirectories()
                setupWorkspaceFiles(executionRoot)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Resolve, import and plan.
                val executor = WorkflowExecutor.filesystem(ClasspathStepLibrary(), demoResultsDir)
                val prepared = executor.prepare(loaded.source, loaded.descriptor)
                prepared.plan.validate()
                println("Execution plan generated (${prepared.plan.steps.size} steps)")

                // 4. Execute
                DemoRun.execute(executor, prepared, runId) ?: return

                // 5. Read and display results from summary.json
                // The workflow creates a directory structure: <workflowName>/run_<runId>/steps/<stepIndex>_<stepName>/outputs/
                val summaryFile = demoResultsDir.resolve(
                    "$workflowName/run_${runId}/steps/03_analyse_bgm_and_steps/outputs/summary-json.json"
                )
                if (summaryFile.exists()) {
                    printAnalysisSummary(summaryFile.readText())
                } else {
                    println("Summary output not found at: $summaryFile")
                }

                println()
                DemoRun.divider()
                println("Demo completed successfully!")
                println("Results saved to: $demoResultsDir")
                DemoRun.divider()

            } catch (e: Exception) {
                println("Error during demo execution: ${e.message}")
                e.printStackTrace()
            }
        }


        private fun getDemoResultsDirectory(): Path = DemoIo.demoResultsDir("diafocus").toPath()

        /**
         * Places the run's files under [executionRoot], the working directory a
         * step's command runs in.
         *
         * This workflow references no library steps, so it is imported and executed
         * without the resolve-and-provision path: its declared file input is read
         * from the working directory as written, rather than being staged there.
         * Both the dataset and the scripts therefore sit under the execution root.
         */
        private fun setupWorkspaceFiles(executionRoot: Path) {
            val dataDir = executionRoot.resolve("data")
            dataDir.createDirectories()
            copyResourceFile("data/diafocus_mock.json", dataDir.resolve("diafocus_mock.json"))

            val scriptsDir = executionRoot.resolve("scripts")
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

