package carp.dsp.demo.eval

import java.io.File

/**
 * Dependency-drift eval.
 *
 * Demonstrates the failure mode CARP-DSP prevents: an unpinned analysis dependency silently
 * changing scientific outputs. The Use Case of walking-speed pipeline is run over every recording
 * in the mobgap LabExample dataset under several released versions of mobgap, holding the input
 * data and the pipeline code fixed so the library version is the only variable.
 *
 * Requires pixi on PATH (https://pixi.sh), the same tool CARP-DSP uses for the workflow env.
 *
 * Run from the demo menu (id: dependency-drift-eval):
 *   ./gradlew :carp.dsp.demo:run --args "run dependency-drift-eval"
 *
 * Writes eval_results/drift-per-datapoint.csv, drift-summary.{txt,csv}, drift-table.tex, and
 * eval_results/fig-dependency-drift.{png,pdf}. First run is slow (pixi solves one env per version);
 * later runs reuse the cached envs under build/drift-pixi.
 */

private object DriftAnchor

fun main() {
    val projectRoot = driftProjectRoot()
    val scriptsDir = projectRoot.resolve("src/jvmMain/resources/scripts/eval")
    val driver = scriptsDir.resolve("drift_experiment.py")
    val evalResults = projectRoot.resolve("eval_results").apply { mkdirs() }
    val figStem = evalResults.resolve("fig-dependency-drift")
    // Keep the pixi project (manifest, lock, envs) under build/ (git-ignored, removed by
    // `gradle clean`), never inside resources/ (it must not be packaged into the jar).
    val workDir = projectRoot.resolve("build/drift-pixi")

    if (!driver.exists()) {
        System.err.println("Drift driver not found at ${driver.absolutePath}")
        return
    }

    val python = detectPython()
    if (python == null) {
        System.err.println("No Python interpreter found (tried python3, python). Install Python to run this eval.")
        return
    }

    println("Running dependency-drift experiment (first run has pixi solve one env per mobgap version)...")
    val command = mutableListOf(
        python, driver.absolutePath,
        "--out", evalResults.absolutePath,
        "--fig", figStem.absolutePath,
        "--work-dir", workDir.absolutePath,
    )
    // Override the conda-forge Python pixi provisions for every env (default 3.11).
    System.getenv("CARP_DRIFT_PY_VERSION")?.takeIf { it.isNotBlank() }?.let {
        command += listOf("--py-version", it)
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().forEachLine { println(it) }
    val code = process.waitFor()

    if (code != 0) {
        System.err.println("Drift experiment exited with code $code")
        return
    }
    val summary = evalResults.resolve("drift-summary.txt")
    if (summary.exists()) {
        println()
        println(summary.readText())
    }
    println("Wrote drift-per-datapoint.csv, drift-summary.{txt,csv}, drift-table.tex, and fig-dependency-drift.{png,pdf}")
    println("to: ${evalResults.absolutePath}")
}

/** First interpreter of [python3, python] that responds to --version. */
private fun detectPython(): String? =
    listOf("python3", "python").firstOrNull { exe ->
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

private fun driftProjectRoot(): File {
    val classPath = DriftAnchor::class.java.protectionDomain.codeSource.location.toURI().path
    return File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
        ?: throw IllegalStateException("Cannot determine project root")
}
