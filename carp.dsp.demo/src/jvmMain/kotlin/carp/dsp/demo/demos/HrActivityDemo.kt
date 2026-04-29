package carp.dsp.demo.demos

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.packaging.PackageBuilder
import carp.dsp.core.application.snakemake.DspToSnakemakeTranslator
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.registry.RegistryClient
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import health.workflows.interfaces.model.WorkflowArtifactPackage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*
import kotlin.math.abs

/**
 * Heart Activity Summary Demo
 *
 * A three-step wearable data pipeline:
 *   1. Load — generate synthetic HR + step data (7 days, hourly)
 *   2. Compute Features — daily mean/resting/peak HR, steps, active hours
 *   3. Visualize — 2x2 summary PNG
 *
 * Run normally:
 *   ./gradlew :carp.dsp.demo:run --args="run hr-activity"
 *
 * Run with full e2e chain (start HWF server -> validate CWL -> publish -> fetch -> run via RAPIDS -> compare):
 *   ./gradlew :carp.dsp.demo:run --args="run hr-activity --e2e"
 */
class HrActivityDemo {
    companion object {

        fun run() = executeDemo(e2e = false)

        fun run(args: List<String>) = executeDemo(e2e = "--e2e" in args)

        @OptIn(ExperimentalPathApi::class)
        private fun executeDemo(e2e: Boolean) {
            val resultsDir = getDemoResultsDirectory()
            val runId = UUID.parse("00000000-0000-0000-0000-000000000004")
            val workflowSlug = "heart_activity_summary"

            println("=" * 60)
            println("Heart Activity Summary Demo" + if (e2e) "  [e2e mode]" else "")
            println("=" * 60)
            println()

            if (resultsDir.exists()) resultsDir.deleteRecursively()
            resultsDir.createDirectories()

            // 1. Load and parse workflow
            val yaml = loadWorkflowYaml()
            val descriptor = WorkflowYamlCodec().decodeOrThrow(yaml)
            println("Workflow: ${descriptor.metadata.name}  (${descriptor.steps.size} steps)")

            // 2. Copy scripts into execution root
            val executionRoot = resultsDir.resolve("$workflowSlug/run_$runId")
            executionRoot.createDirectories()
            setupWorkspaceFiles(executionRoot)
            println("Workspace: $executionRoot")
            println()

            // 3. Plan + Execute
            val definition = WorkflowDescriptorImporter().import(descriptor)
            val plan = DefaultExecutionPlanner().plan(definition)
            plan.validate()

            val executor = DefaultPlanExecutor(
                workspaceManager = DefaultWorkspaceManager(resultsDir),
                artefactStore = FileSystemArtefactStore(resultsDir.resolve("artifacts")),
            )
            println("Running pipeline...")
            println("-" * 60)
            val report = executor.execute(plan, runId)
            println("-" * 60)

            if (report.status.toString() != "SUCCEEDED") {
                println(Color.red("Execution failed: ${report.status}"))
                return
            }
            println(Color.green("Execution succeeded [OK]"))
            println()

            // 4. Print feature summary
            resultsDir.walk().firstOrNull { it.name.endsWith(".csv") && "features" in it.name }
                ?.let { printFeatureSummary(it.readText()) }

            resultsDir.walk().firstOrNull { it.name.endsWith(".png") }
                ?.let { println("Plot saved: $it") }

            // 5. Package + show CWL
            println()
            println("-" * 60)
            println("Packaging workflow...")
            val pkg = PackageBuilder.build(descriptor)
            println("Package : ${pkg.id} @ ${pkg.version}")
            println("Hash    : ${pkg.contentHash.take(16)}...")
            if (pkg.cwl != null) {
                println()
                println("CWL (first 8 lines):")
                pkg.cwl!!.content.lines().take(8).forEach { println("  $it") }
                println("  ...")
            }

            // 6. E2E chain
            if (e2e) runE2eChain(pkg, resultsDir)

            println()
            println("=" * 60)
            println(Color.green("Demo complete. Results: $resultsDir"))
            println("=" * 60)
        }

        // -- Snakemake chain --------------------------------------------------

        @OptIn(ExperimentalPathApi::class)
        private fun runSnakemakeChain(pkg: WorkflowArtifactPackage, apiKey: String, resultsDir: Path): Path {
            println()
            println("=" * 60)
            println("Snakemake Chain")
            println("=" * 60)

            val snakemakeDir = resultsDir.parent.resolve("hr_activity_snakemake")
            if (snakemakeDir.exists()) snakemakeDir.deleteRecursively()
            snakemakeDir.createDirectories()

            // 1. Fetch package from server
            println("[snakemake] Fetching ${pkg.id} @ ${pkg.version} from server...")
            val client = RegistryClient(
                http = HttpClient(CIO) { install(ContentNegotiation) { json() } },
                baseUrl = "http://localhost:8080",
                apiKey = apiKey,
            )
            val fetched = runBlocking { client.getComponent(pkg.id, pkg.version) }
            println(Color.green("[snakemake] Package fetched [OK]"))

            // 2. Decode workflow from native YAML
            val descriptor = WorkflowYamlCodec().decodeOrThrow(fetched.native.content)
            println("[snakemake] Decoded: ${descriptor.metadata.name} (${descriptor.steps.size} steps)")

            // 3. Generate Snakefile
            val snakefile = DspToSnakemakeTranslator.translate(descriptor)
            snakemakeDir.resolve("Snakefile").writeText(snakefile)
            println("[snakemake] Snakefile written to $snakemakeDir")
            println()
            println("Snakefile preview:")
            snakefile.lines().take(20).forEach { println("  $it") }
            if (snakefile.lines().size > 20) println("  ...")
            println()

            // 4. Copy scripts
            setupWorkspaceFiles(snakemakeDir)
            println("[snakemake] Scripts ready")

            // 5. Run via RAPIDS container
            println("[snakemake] Running via moshiresearch/rapids:latest...")
            println("-" * 60)
            val absPath = snakemakeDir.toAbsolutePath().toString().replace("\\", "/")
            val cmd = arrayOf(
                "docker", "run", "--rm",
                "-v", "$absPath:/workspace",
                "-w", "/workspace",
                "moshiresearch/rapids:latest",
                "snakemake", "--cores", "1",
            )
            val exit = runProcess(*cmd)
            println("-" * 60)
            if (exit == 0) println(Color.green("[snakemake] Run complete [OK]"))
            else           println(Color.red("[snakemake] Snakemake exited with code $exit"))

            println("Results: $snakemakeDir")
            return snakemakeDir
        }

        // -- E2E chain ------------------------------------------------------

        private fun runE2eChain(pkg: WorkflowArtifactPackage, resultsDir: Path) {
            println()
            println("=" * 60)
            println("E2E Chain")
            println("=" * 60)

            val (serverStarted, apiKey) = ensureServerRunning()

            try {
                // 1. Validate CWL
                if (pkg.cwl != null) validateCwl(pkg.cwl!!.content, resultsDir)
                else println("[cwl] No CWL generated — skipping validation")

                // 2. Publish
                publishPackage(pkg, baseUrl = "http://localhost:8080", apiKey = apiKey)

                // 3. Fetch + Snakemake
                val snakemakeDir = runSnakemakeChain(pkg, apiKey, resultsDir)

                // 4. Compare
                compareArtifacts(resultsDir, snakemakeDir)

            } finally {
                if (serverStarted) stopServer("hwf-demo")
            }
        }

        // -- Artifact comparison --------------------------------------------

        private fun compareArtifacts(resultsDir: Path, snakemakeDir: Path) {
            println()
            println("=" * 60)
            println("Artifact Comparison: DSP vs Snakemake/RAPIDS")
            println("=" * 60)

            // Feature CSV
            val dspCsv   = resultsDir.walk().filter { it.name.endsWith(".csv") && "features" in it.name }.firstOrNull()
            val snakeCsv = snakemakeDir.resolve("daily_features_csv.csv").takeIf { it.exists() }

            if (dspCsv == null || snakeCsv == null) {
                println(Color.red("[compare] Could not find both feature CSVs — skipping"))
            } else {
                println("[compare] DSP:       $dspCsv")
                println("[compare] Snakemake: $snakeCsv")
                println()

                val dspRows   = parseCsvRows(dspCsv.readText())
                val snakeRows = parseCsvRows(snakeCsv.readText())

                if (dspRows.size != snakeRows.size) {
                    println(Color.red("[compare] Row count mismatch: DSP=${dspRows.size} Snakemake=${snakeRows.size}"))
                } else {
                    val headers = dspRows.firstOrNull()?.keys?.toList() ?: emptyList()
                    println("  ${"date".padEnd(12)}  ${"mean_hr".padEnd(8)}  ${"steps".padEnd(8)}  match")
                    println("  " + "-" * 46)

                    var allMatch = true
                    dspRows.zip(snakeRows).forEach { (dRow, sRow) ->
                        val rowMatch = headers.all { h ->
                            val dv = dRow[h] ?: ""; val sv = sRow[h] ?: ""
                            dv == sv || (dv.toDoubleOrNull() != null && sv.toDoubleOrNull() != null
                                && abs(dv.toDouble() - sv.toDouble()) < 0.01)
                        }
                        if (!rowMatch) allMatch = false
                        val marker = if (rowMatch) Color.green("[OK]") else Color.red("[DIFF]")
                        println("  ${(dRow["date"] ?: "?").padEnd(12)}  " +
                                "${(dRow["mean_hr"] ?: "?").padEnd(8)}  " +
                                "${(dRow["total_steps"] ?: "?").padEnd(8)}  $marker")
                    }
                    println()
                    if (allMatch) println(Color.green("[compare] Feature data matches [OK]"))
                    else          println(Color.red("[compare] Differences found in feature data"))
                }
            }

            // PNG sizes
            println()
            val dspPng   = resultsDir.walk().filter { it.name.endsWith(".png") }.firstOrNull()
            val snakePng = snakemakeDir.resolve("summary_plot_png.png").takeIf { it.exists() }

            if (dspPng != null && snakePng != null) {
                val dspSize   = dspPng.fileSize()
                val snakeSize = snakePng.fileSize()
                val diffPct   = if (dspSize > 0) abs(dspSize - snakeSize) * 100 / dspSize else 100
                println("[compare] PNG  DSP: ${dspSize}B   Snakemake: ${snakeSize}B   diff: $diffPct%")
                if (diffPct < 10) println(Color.green("[compare] PNG sizes consistent [OK]"))
                else              println(Color.yellow("[compare] PNG sizes differ by $diffPct%"))
            } else {
                println(Color.yellow("[compare] One or both PNGs not found — skipping PNG check"))
            }
        }

        // -- Server helpers -------------------------------------------------

        private fun ensureServerRunning(): Pair<Boolean, String> {
            val port = 8080
            val apiKey = "demo-e2e-key"
            val image = "ghcr.io/carp-dk/hwf-server:latest"
            val containerName = "hwf-demo"

            val existing = captureProcess("docker", "ps", "--filter", "publish=$port", "--format", "{{.ID}}")
            if (existing.trim().isNotEmpty()) {
                println("[server] Already running on port $port — using existing instance")
                return Pair(false, System.getenv("HWF_API_KEY") ?: "dev-local-key-change-in-production")
            }

            println("[server] Pulling $image...")
            runProcess("docker", "pull", image)
            println("[server] Starting container...")
            runProcess("docker", "run", "-d", "--name", containerName,
                "-p", "$port:$port", "-e", "HWF_API_KEY=$apiKey", image)

            println("[server] Waiting for server to be ready...")
            var ready = false
            for (attempt in 1..20) {
                Thread.sleep(2000)
                try {
                    val conn = URL("http://localhost:$port/api/v1/components/search")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write("{}".toByteArray()) }
                    if (conn.responseCode == 200) ready = true
                    conn.disconnect()
                } catch (_: Exception) { }
                if (ready) { println(Color.green("[server] Ready [OK] (attempt $attempt)")); break }
                else println("[server] Waiting... ($attempt/20)")
            }
            if (!ready) { stopServer(containerName); error("HWF server did not become ready in time") }
            return Pair(true, apiKey)
        }

        private fun stopServer(containerName: String) {
            println("[server] Stopping $containerName...")
            runProcess("docker", "stop", containerName)
            runProcess("docker", "rm", containerName)
        }

        private fun validateCwl(cwlContent: String, workDir: Path) {
            println()
            println("[cwl] Validating CWL with cwltool...")
            val cwlFile = workDir.resolve("workflow.cwl")
            cwlFile.writeText(cwlContent)
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val cwlPath = if (isWindows) {
                val abs = cwlFile.toAbsolutePath().toString()
                "/mnt/" + abs[0].lowercaseChar() + abs.drop(2).replace("\\", "/")
            } else cwlFile.toAbsolutePath().toString()
            val cmd = if (isWindows) arrayOf("wsl", "cwltool", "--validate", cwlPath)
                      else           arrayOf("cwltool", "--validate", cwlPath)
            val exit = runProcess(*cmd)
            if (exit == 0) println(Color.green("[cwl] Validation passed [OK]"))
            else           println(Color.red("[cwl] Validation failed (exit $exit)"))
        }

        private fun publishPackage(pkg: WorkflowArtifactPackage, baseUrl: String, apiKey: String) {
            println()
            println("[publish] Publishing ${pkg.id} @ ${pkg.version} to $baseUrl...")
            val client = RegistryClient(
                http = HttpClient(CIO) { install(ContentNegotiation) { json() } },
                baseUrl = baseUrl, apiKey = apiKey,
            )
            val result = runBlocking { client.publish(pkg) }
            if (result.accepted)
                println(Color.green("[publish] Accepted [OK]  id=${result.id}  version=${result.version}"))
            else
                println(Color.red("[publish] Server rejected the package"))
        }

        // -- General helpers ------------------------------------------------

        private fun loadWorkflowYaml(): String =
            (HrActivityDemo::class.java.classLoader
                .getResource("workflows/hr-activity-summary.yaml")
                ?: error("Workflow YAML not found"))
                .readText()

        private fun getDemoResultsDirectory(): Path {
            val cp = HrActivityDemo::class.java.protectionDomain.codeSource.location.toURI().path
            val root = java.io.File(cp).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                ?: error("Cannot determine project root")
            return root.toPath().resolve("demo_results").resolve("hr_activity")
        }

        private fun setupWorkspaceFiles(dir: Path) {
            val scriptsDir = dir.resolve("scripts/hr_activity")
            scriptsDir.createDirectories()
            listOf("load_data.py", "compute_features.py", "visualize.py").forEach { name ->
                copyResource("scripts/hr_activity/$name", scriptsDir.resolve(name))
            }
        }

        private fun copyResource(resource: String, target: Path) {
            val url = HrActivityDemo::class.java.classLoader.getResource(resource)
                ?: error("Resource not found: $resource")
            Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun parseCsvRows(text: String): List<Map<String, String>> {
            val lines = text.trim().lines()
            if (lines.size < 2) return emptyList()
            val headers = lines[0].split(",")
            return lines.drop(1).map { line -> headers.zip(line.split(",")).toMap() }
        }

        private fun runProcess(vararg cmd: String): Int =
            ProcessBuilder(*cmd).inheritIO().start().waitFor()

        private fun captureProcess(vararg cmd: String): String =
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
                .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

        private fun printFeatureSummary(csv: String) {
            val lines = csv.trim().lines()
            if (lines.size < 2) return
            val headers = lines[0].split(",")
            println("Daily Feature Summary")
            println("-" * 60)
            lines.drop(1).forEach { line ->
                val vals = line.split(",")
                val row = headers.zip(vals).toMap()
                println("  ${row["date"]}  " +
                    "HR: mean=${row["mean_hr"]} resting=${row["resting_hr"]} peak=${row["peak_hr"]}  " +
                    "steps=${row["total_steps"]}  active=${row["active_hours"]}h")
            }
            println()
        }

        private operator fun String.times(n: Int) = repeat(n)

        // -- ANSI color helpers ---------------------------------------------

        private object Color {
            private val ESC    = ""
            val GREEN  = "${ESC}[32m"
            val RED    = "${ESC}[31m"
            val YELLOW = "${ESC}[33m"
            val RESET  = "${ESC}[0m"

            fun green(s: String)  = "$GREEN$s$RESET"
            fun red(s: String)    = "$RED$s$RESET"
            fun yellow(s: String) = "$YELLOW$s$RESET"
        }
    }
}
