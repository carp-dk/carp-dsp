package carp.dsp.demo.demos

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

        @OptIn(ExperimentalPathApi::class)
        private fun executeDemo() {
            val demoResultsDir = getDemoResultsDirectory()
            val runId = UUID.parse("00000000-0000-0000-0000-000000000002")

            try {
                println("=" * 70)
                println("Mobgap Gait Analysis Demo")
                println("=" * 70)
                println()

                // Clean up previous results
                if (demoResultsDir.exists()) {
                    println("Cleaning up previous results...")
                    demoResultsDir.deleteRecursively()
                }
                demoResultsDir.createDirectories()

                // 1. Load YAML workflow
                val workflowYaml = loadWorkflowYaml()
                val descriptor = WorkflowYamlCodec().decodeOrThrow(workflowYaml)
                println("Workflow loaded: ${descriptor.metadata.name}")

                // 2. Set up workspace (scripts only — dataset is downloaded by the import step)
                setupWorkspaceFiles(demoResultsDir)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Import and plan
                val definition = WorkflowDescriptorImporter().import(descriptor)
                val planner = DefaultExecutionPlanner()
                val plan = planner.plan(definition)
                plan.validate()
                println("Execution plan generated (${plan.steps.size} steps)")

                // 4. Set up executor
                val artefactStore = FileSystemArtefactStore(demoResultsDir.resolve("artifacts"))
                val workspaceManager = DefaultWorkspaceManager(demoResultsDir)
                val executor = DefaultPlanExecutor(
                    workspaceManager = workspaceManager,
                    artefactStore = artefactStore
                )

                // 5. Execute
                println()
                println("Executing workflow...")
                println("(Step 1 will download the LabExampleDataset on first run — this may take a moment)")
                println("-" * 70)
                val report = executor.execute(plan, runId)
                println("-" * 70)

                if (report.status.toString() != "SUCCEEDED") {
                    println("Workflow execution failed: ${report.status}")
                    report.issues.forEach { issue ->
                        println("   - ${issue.message}")
                    }
                    return
                }
                println("Workflow execution succeeded")
                println()

                // 6. Read and display aggregated DMO results
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
                println("=" * 70)
                println("Demo completed successfully!")
                println("Results saved to: $demoResultsDir")
                println("=" * 70)

            } catch (e: Exception) {
                println("Error during demo execution: ${e.message}")
                e.printStackTrace()
            }
        }

        private fun loadWorkflowYaml(): String {
            val resource = MobgapDemo::class.java.classLoader
                .getResource("workflows/mobgap-gait-analysis.yaml")
                ?: throw IllegalStateException(
                    "Workflow YAML not found: workflows/mobgap-gait-analysis.yaml"
                )
            return resource.readText()
        }

        @OptIn(ExperimentalPathApi::class)
        private fun getDemoResultsDirectory(): Path {
            val classPath = MobgapDemo::class.java.protectionDomain.codeSource.location.toURI().path
            val projectRoot = java.io.File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                ?: throw IllegalStateException("Cannot determine project root")
            return projectRoot.toPath().resolve("demo_results").resolve("mobgap")
        }

        private fun setupWorkspaceFiles(tmpDir: Path) {
            // Copy workflow YAML
            val workflowsDir = tmpDir.resolve("resources/workflows")
            workflowsDir.createDirectories()
            val workflowResource = MobgapDemo::class.java.classLoader
                .getResource("workflows/mobgap-gait-analysis.yaml")
                ?: throw IllegalStateException("Workflow YAML not found")
            Files.copy(
                workflowResource.openStream(),
                workflowsDir.resolve("mobgap-gait-analysis.yaml"),
                StandardCopyOption.REPLACE_EXISTING
            )

            // Copy mobgap scripts
            val scriptsDir = tmpDir.resolve("resources/scripts/mobgap")
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

        private fun copyResourceFile(resourcePath: String, targetPath: Path) {
            val resource = MobgapDemo::class.java.classLoader.getResource(resourcePath)
                ?: throw IllegalStateException("Resource not found: $resourcePath")
            Files.copy(
                resource.openStream(),
                targetPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }

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
            if (value != null && value.isNotEmpty()) {
                val formatted = try { "%.3f".format(value.toDouble()) } catch (_: Exception) { value }
                println("$label: $formatted $unit")
            }
        }

        private operator fun String.times(count: Int): String = repeat(count)
    }
}
