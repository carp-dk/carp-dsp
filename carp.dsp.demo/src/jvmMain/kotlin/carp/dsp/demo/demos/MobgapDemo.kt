package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.DemoRun
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Mobgap Gait Analysis Demo (https://github.com/mobilise-d/mobgap)
 *
 * Demonstrates an 8-step DSP workflow that:
 * 1. Downloads mobgap's LabExampleDataset (MS cohort IMU recording)
 * 2. Detects gait sequences (GSD)
 * 3. Detects initial contacts and classifies laterality (ICD)
 * 4. Estimates per-second gait parameters (cadence, stride length, walking speed)
 * 5. Assembles walking bouts (WBA)
 * 6. Aggregates digital mobility outcomes (DMOs)
 * 7. Plots walking-bout parameter trends
 * 8. Plots aggregated DMO summary metrics
 *
 * Workflow YAML: resources/workflows/mobgap-gait-analysis.yaml
 */
class MobgapDemo {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            run()
        }

        fun run() {
            executeDemo()
        }

        private fun executeDemo() {
            val demoResultsDir = getDemoResultsDirectory()
            val runId = UUID.parse("00000000-0000-0000-0000-000000000002")

            try {
                DemoRun.banner("Mobgap Gait Analysis Demo")
                DemoRun.freshResultsDir(demoResultsDir)

                // 1. Load the workflow. It is written into the results directory,
                // which is what its steps.lock and relative inputs resolve against.
                val loaded = DemoRun.loadWorkflow("workflows/mobgap-gait-analysis.yaml", demoResultsDir)

                // 2. Set up workspace (scripts only — dataset is downloaded by the import step).
                // Task script paths are relative to the execution root, which is the
                // working directory a step's command runs in.
                val executionRoot = demoResultsDir.resolve("mobgap_gait_analysis_pipeline/run_$runId")
                executionRoot.createDirectories()
                setupWorkspaceFiles(executionRoot)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Resolve, import and plan.
                val executor = WorkflowExecutor.filesystem(ClasspathStepLibrary(), demoResultsDir)
                val prepared = executor.prepare(loaded.source, loaded.descriptor)
                prepared.plan.validate()
                println("Execution plan generated (${prepared.plan.steps.size} steps)")

                // 4. Execute
                DemoRun.execute(
                    executor, prepared, runId,
                    notice = "(Step 1 will download the LabExampleDataset on first run — this may take a moment)"
                ) ?: return

                // 5. Read and display aggregated DMO results
                val workflowName = "mobgap_gait_analysis_pipeline"
                val outputFile = demoResultsDir.resolve(
                    "$workflowName/run_${runId}/steps/06_dmo_aggregation/outputs/aggregated-dmos-csv.csv"
                )
                if (outputFile.exists()) {
                    printDmoResults(outputFile.readText())
                } else {
                    println("Output not found at: $outputFile")
                    println("Scanning for output files...")
                    demoResultsDir.walk()
                        .filter { it.name.endsWith(".csv") }
                        .forEach { println("  Found: $it") }
                }

                val runRoot = demoResultsDir.resolve("$workflowName/run_${runId}")
                printPlotLocations(runRoot)

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


        private fun getDemoResultsDirectory(): Path = DemoIo.demoResultsDir("mobgap").toPath()

        /**
         * Copies the pipeline's scripts under [executionRoot], the working directory
         * a step's command runs in, so the relative script paths in the workflow
         * resolve. The dataset is not copied: the import step downloads it.
         */
        private fun setupWorkspaceFiles(executionRoot: Path) {
            val scriptsDir = executionRoot.resolve("scripts/mobgap")
            scriptsDir.createDirectories()
            listOf(
                "import_data.py",
                "gsd.py",
                "icd.py",
                "per_sec_params.py",
                "wba.py",
                "aggregate.py",
                "plot_wb_params.py",
                "plot_aggregated_dmos.py"
            )
                .forEach { script ->
                    copyResourceFile("scripts/mobgap/$script", scriptsDir.resolve(script))
                }
        }

        private fun printPlotLocations(runRoot: Path) {
            val candidates = listOf(
                runRoot.resolve("steps/07_plot_walking_bout_parameters/outputs/wb-params-plot-png.png"),
                runRoot.resolve("steps/08_plot_aggregated_dmos/outputs/aggregated-dmos-plot-png.png")
            )
            val existing = candidates.filter { it.exists() }
            if (existing.isNotEmpty()) {
                println()
                println("Generated plots:")
                existing.forEach { println("  - $it") }
            }
        }

        private fun copyResourceFile(resourcePath: String, targetPath: Path) =
            DemoIo.copyResource(resourcePath, targetPath)

        private fun printDmoResults(csvText: String) {
            println("AGGREGATED DIGITAL MOBILITY OUTCOMES")
            println("-" * 70)
            try {
                val lines = csvText.trim().lines()
                if (lines.size < 2) {
                    println("No data rows found.")
                    return
                }
                val headers = lines[0].split(",").map { it.trim() }
                val values = lines[1].split(",").map { it.trim() }
                val row = headers.zip(values).toMap()

                println()
                println("Walking Speed:")
                printMetric("  Mean", row["mean_walking_speed_mps"], "m/s")
                printMetric("  Median", row["median_walking_speed_mps"], "m/s")

                println()
                println("Cadence:")
                printMetric("  Mean", row["mean_cadence_spm"], "steps/min")
                printMetric("  Median", row["median_cadence_spm"], "steps/min")

                println()
                println("Stride Length:")
                printMetric("  Mean", row["mean_stride_length_m"], "m")
                printMetric("  Median", row["median_stride_length_m"], "m")

                println()
                println("Stride Duration:")
                printMetric("  Mean", row["mean_stride_duration_s"], "s")
                printMetric("  Median", row["median_stride_duration_s"], "s")

                val nWbs = row["n_walking_bouts"] ?: row["n_wbs"]
                val nStrides = row["n_strides"] ?: row["total_strides"]
                println()
                if (nWbs != null) println("  Walking bouts:  $nWbs")
                if (nStrides != null) println("  Total strides:  $nStrides")

                // Print all columns as fallback if key columns missing
                if (row["mean_walking_speed_mps"] == null) {
                    println()
                    println("(Raw DMO columns:)")
                    row.forEach { (k, v) -> println("  $k: $v") }
                }

                println()
                println("-" * 70)
            } catch (e: Exception) {
                println("Could not parse results: ${e.message}")
                println("Raw output:")
                println(csvText)
            }
        }

        private fun printMetric(label: String, value: String?, unit: String) {
            if (!value.isNullOrEmpty()) {
                val formatted = try { "%.3f".format(value.toDouble()) } catch (_: Exception) { value }
                println("$label: $formatted $unit")
            }
        }

        private operator fun String.times(count: Int): String = repeat(count)
    }
}
