package carp.dsp.demo.demos

import carp.dsp.demo.DemoRegistry
import carp.dsp.demo.api.CliDemo
import carp.dsp.demo.api.Demo
import kotlin.system.exitProcess

/**
 * Runs every registered demo and reports which passed.
 *
 * Nothing else runs the demos: they are not part of `build`, so a demo can rot
 * for weeks - a stale path, a workspace it no longer sets up correctly - and
 * only a person deciding to run it finds out. This makes checking all of them
 * one command, and one summary.
 *
 * Demos are discovered from [DemoRegistry] rather than listed here, so a new
 * demo is covered the moment it is registered. Evaluations are excluded by
 * default: they are long-running measurement harnesses rather than checks, and
 * `run-all-evals` already drives them.
 *
 * Exits non-zero when any demo fails, so it is usable as a check rather than
 * something whose output has to be read carefully.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:run --args="run run-all-demos"
 *   ./gradlew :carp.dsp.demo:run --args="run run-all-demos --skip mobgap,library-reuse"
 *   ./gradlew :carp.dsp.demo:run --args="run run-all-demos --only library-offline,diafocus"
 *   ./gradlew :carp.dsp.demo:run --args="run run-all-demos --include-evals"
 */
object RunAllDemosRegisteredDemo : CliDemo {
    override val id: String = "run-all-demos"
    override val title: String = "Run all demos and report pass/fail"

    override fun run() = run(emptyList())

    override fun run(args: List<String>) {
        val only = args.valueOf("--only").orEmpty()
        val skip = args.valueOf("--skip").orEmpty()
        val includeEvals = "--include-evals" in args

        val selected = DemoRegistry.demos
            .filter { it.id != id }
            .filter { includeEvals || it.category != "eval" }
            .filter { only.isEmpty() || it.id in only }
            .filter { it.id !in skip }

        if (selected.isEmpty()) {
            println("No demos selected.")
            return
        }

        println("Running ${selected.size} demo(s)...")
        println()

        val results = selected.map { demo -> runOne(demo) }

        println()
        println("=".repeat(72))
        println("Summary")
        println("=".repeat(72))
        results.forEach { result ->
            val mark = if (result.passed) green("PASS") else red("FAIL")
            println("  $mark  ${result.id.padEnd(26)} ${result.durationMs / 1000.0}s".trimEnd())
            result.failure?.let { println("        ${it.lines().first()}") }
        }

        val failed = results.count { !it.passed }
        println("-".repeat(72))
        println(
            if (failed == 0) green("All ${results.size} demo(s) passed")
            else red("$failed of ${results.size} demo(s) failed")
        )
        println("=".repeat(72))

        // Non-zero exit so a failure is visible to Gradle and CI, not just in the log.
        if (failed > 0) exitProcess(1)
    }

    private fun runOne(demo: Demo): Result {
        println("-".repeat(72))
        println("Running: ${demo.id} - ${demo.title}")
        println("-".repeat(72))

        val startedAt = System.currentTimeMillis()
        return try {
            demo.run()
            Result(demo.id, passed = true, durationMs = System.currentTimeMillis() - startedAt)
        } catch (e: Throwable) {
            // A demo failing must not stop the rest of the run, and a demo can fail
            // in any way at all - including an Error from a missing native library -
            // so everything is caught and reported rather than propagated.
            System.err.println("${demo.id} failed: ${e.message}")
            Result(
                demo.id,
                passed = false,
                durationMs = System.currentTimeMillis() - startedAt,
                failure = "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    private data class Result(
        val id: String,
        val passed: Boolean,
        val durationMs: Long,
        val failure: String? = null,
    )

    /** Reads a comma-separated option, e.g. `--skip mobgap,diafocus`. */
    private fun List<String>.valueOf(flag: String): List<String>? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }
            ?.let { this[it + 1].split(",").map(String::trim).filter(String::isNotEmpty) }

    // Colour is skipped when NO_COLOR is set or output is not a terminal, so a
    // piped log stays readable.
    private val useColour: Boolean =
        System.getenv("NO_COLOR") == null && System.console() != null

    private fun green(text: String) = colour(text, "32")

    private fun red(text: String) = colour(text, "31")

    private fun colour(text: String, code: String) =
        if (useColour) "\u001B[${code}m$text\u001B[0m" else text
}
