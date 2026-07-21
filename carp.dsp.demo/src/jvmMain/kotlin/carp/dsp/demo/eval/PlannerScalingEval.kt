package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.io.DemoIo
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import java.io.File

/**
 * Planner-scaling eval.
 *
 * Times the plan-time gate as workflow size grows. Synthetic linear-chain workflows of
 * N steps are generated in memory (each step consumes the previous step's typed CSV
 * output, mirroring the shape of the real gait and HR pipelines) and pushed through the
 * same decode -> import -> plan -> validate path the real workflows use.
 *
 * For each size the four phases are timed separately over R repeats (after JIT warm-up),
 * so the figure can show both the total author-facing latency and where it goes.
 * Every synthetic workflow must plan clean; the run aborts if any ERROR issue appears.
 *
 * Run (from the demo menu, id: planner-scaling-eval):
 *   ./gradlew :carp.dsp.demo:run --args "run planner-scaling-eval"
 * or directly:
 *   ./gradlew :carp.dsp.demo:evalPlannerScaling
 *   ./gradlew :carp.dsp.demo:evalPlannerScaling -Pargs="2,5,10,20,50,100,200 50"
 * Arg 1 (optional): comma-separated sizes. Arg 2 (optional): repeats per size.
 *
 * Writes eval_results/planner-scaling.csv (one row per repeat), appends a summary to
 * eval_results/planner-scaling.txt, then rebuilds eval_results/fig-planner-scaling.pdf by
 * running scripts/eval/plot_planner_scaling.py in the eval pixi env (has matplotlib),
 * falling back to the system Python if pixi is not on PATH.
 */

/** Same fixed namespace as the other evals (UUID v5 determinism). */
private val PS_FIXED_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")

private val DEFAULT_SIZES = listOf(2, 5, 10, 20, 50, 100, 200)
private const val DEFAULT_REPEATS = 50
private const val WARMUP_ITERATIONS = 25

private data class PhaseSample(
    val decodeMs: Double,
    val importMs: Double,
    val planMs: Double,
    val validateMs: Double
) {
    val totalMs: Double get() = decodeMs + importMs + planMs + validateMs
}

fun main(args: Array<String>) {
    val sizes = args.firstOrNull()
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_SIZES
    val repeats = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_REPEATS

    val codec = WorkflowYamlCodec()

    // Warm-up: JIT-compile the whole path on a mid-size workflow before timing anything.
    val warmupYaml = syntheticChainYaml(20)
    repeat(WARMUP_ITERATIONS) { timedPlan(codec, warmupYaml) }

    val csv = StringBuilder("size,edges,repeat,decode_ms,import_ms,plan_ms,validate_ms,total_ms\n")
    val summary = mutableListOf<String>()

    for (size in sizes) {
        val yaml = syntheticChainYaml(size)
        val samples = (1..repeats).map { r ->
            val s = timedPlan(codec, yaml)
            csv.append(
                "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f\n".format(
                    size, size - 1, r, s.decodeMs, s.importMs, s.planMs, s.validateMs, s.totalMs
                )
            )
            s
        }
        val medTotal = median(samples.map { it.totalMs })
        val medPlan = median(samples.map { it.planMs + it.validateMs })
        summary += "  %4d steps: total median %8.2f ms  (plan+validate %6.2f ms)".format(size, medTotal, medPlan)
    }

    val report = buildString {
        appendLine("=".repeat(70))
        appendLine("Planner-scaling eval - ${java.time.Instant.now()}")
        appendLine("Synthetic linear chains, sizes $sizes, $repeats repeats each,")
        appendLine("$WARMUP_ITERATIONS warm-up iterations. Phases: decode | import | plan | validate.")
        appendLine("All workflows planned with zero ERROR issues.")
        appendLine()
        summary.forEach { appendLine(it) }
        appendLine("=".repeat(70))
    }
    println(report)

    val dir = DemoIo.evalResultsDir()
    val csvFile = dir.resolve("planner-scaling.csv")
    csvFile.writeText(csv.toString())
    dir.resolve("planner-scaling.txt").appendText(report + "\n")
    println("Wrote planner-scaling.csv and planner-scaling.txt to: ${dir.absolutePath}")

    plotFigure(csvFile, dir.resolve("fig-planner-scaling.pdf"))
}

/**
 * Rebuilds the figure from the CSV, like the other evals: prefer the eval pixi env
 * (scripts/eval/pixi.toml, task `plot-scaling`, has matplotlib), fall back to the
 * system Python if pixi is not on PATH.
 */
private fun plotFigure(csvFile: File, figFile: File) {
    val scriptsDir = DemoIo.projectRoot().resolve("src/jvmMain/resources/scripts/eval")
    val script = scriptsDir.resolve("plot_planner_scaling.py")
    if (!script.exists()) {
        System.err.println("Plot script not found at ${script.absolutePath} - skipping figure.")
        return
    }

    val manifest = scriptsDir.resolve("pixi.toml")
    val command = when {
        psToolAvailable("pixi") && manifest.exists() -> listOf(
            "pixi", "run", "--manifest-path", manifest.absolutePath, "plot-scaling",
            "--csv", csvFile.absolutePath, "--out", figFile.absolutePath
        )
        else -> {
            val python = psDetectPython()
            if (python == null) {
                System.err.println(
                    "Neither pixi nor a Python interpreter found - skipping figure. " +
                    "Rebuild manually: python ${script.absolutePath} --out ${figFile.absolutePath}"
                )
                return
            }
            listOf(python, script.absolutePath, "--csv", csvFile.absolutePath, "--out", figFile.absolutePath)
        }
    }

    println("Rebuilding figure: ${command.joinToString(" ")}")
    val process = ProcessBuilder(command)
        .directory(scriptsDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().forEachLine { println(it) }
    val code = process.waitFor()
    if (code != 0) {
        System.err.println("Figure rebuild exited with code $code (results CSV is unaffected).")
    }
}

/** True if [exe] responds to --version on this machine. */
private fun psToolAvailable(exe: String): Boolean =
    @Suppress("BAN_KOTLIN_TRY_CATCH")
    try {
        ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

/** First interpreter of [python3, python] that responds to --version. */
private fun psDetectPython(): String? =
    listOf("python3", "python").firstOrNull { psToolAvailable(it) }

/** Runs the full path once, timing each phase; aborts if the plan is not clean. */
private fun timedPlan(codec: WorkflowYamlCodec, yaml: String): PhaseSample {
    val t0 = System.nanoTime()
    val descriptor = codec.decodeOrThrow(yaml)
    val t1 = System.nanoTime()
    val definition = WorkflowDescriptorImporter(PS_FIXED_NAMESPACE).import(descriptor)
    val t2 = System.nanoTime()
    val plan = DefaultExecutionPlanner().plan(definition)
    val t3 = System.nanoTime()
    plan.validate()
    val t4 = System.nanoTime()

    val errors = plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
    check(errors.isEmpty()) { "Synthetic workflow planned with ERROR issues: ${errors.map { it.code }}" }

    return PhaseSample(
        decodeMs = (t1 - t0) / 1e6,
        importMs = (t2 - t1) / 1e6,
        planMs = (t3 - t2) / 1e6,
        validateMs = (t4 - t3) / 1e6
    )
}

/**
 * A linear chain of [n] steps: step-000 produces a CSV, every later step consumes the
 * previous step's output and produces its own. One shared pixi environment, one typed
 * connection per edge - the same shape as the real pipelines, scaled.
 */
private fun syntheticChainYaml(n: Int): String {
    require(n >= 1)
    val sb = StringBuilder()
    sb.append(
        """
        schemaVersion: "1.0"
        metadata:
          id: "synthetic-chain-$n"
          name: "Synthetic chain ($n steps)"
          description: "Generated linear-chain workflow for the planner-scaling eval"
          version: "1.0"
          tags:
            - "synthetic"
            - "eval"

        environments:
          env-synth:
            name: "synthetic"
            kind: "pixi"
            spec:
              pythonVersion:
                - "3.11"
              dependencies:
                - "pypi:pandas"

        steps:
        """.trimIndent()
    )
    sb.append("\n")
    for (i in 0 until n) {
        val id = "step-%03d".format(i)
        val prev = "step-%03d".format(i - 1)
        sb.append(
            """
              - id: "$id"
                metadata:
                  name: "Step $i"
                  description: "Synthetic chain step $i"
                  version: "1.0"
                environmentId: "env-synth"
            """.trimIndent().prependIndent("  ")
        )
        sb.append("\n")
        if (i == 0) {
            sb.append("    dependsOn: []\n")
        } else {
            sb.append("    dependsOn:\n      - \"$prev\"\n")
        }
        sb.append(
            """
                task:
                  type: "python"
                  id: "task-$id"
                  name: "$id"
                  entryPoint:
                    type: "script"
                    scriptPath: "scripts/synthetic/step.py"
                  args:
                    - "--output"
                    - "output.0"
            """.trimIndent().prependIndent("    ")
        )
        sb.append("\n")
        if (i > 0) {
            sb.append(
                """
                    inputs:
                      - id: "data-%03d"
                        descriptor:
                          type: "csv"
                          format: "utf-8"
                          notes: "Synthetic chain data"
                        source:
                          type: "step-output"
                          stepId: "$prev"
                          outputId: "data-%03d"
                """.trimIndent().format(i - 1, i - 1).prependIndent("    ")
            )
            sb.append("\n")
        }
        sb.append(
            """
                outputs:
                  - id: "data-%03d"
                    descriptor:
                      type: "csv"
                      format: "utf-8"
                      notes: "Synthetic chain data"
            """.trimIndent().format(i).prependIndent("    ")
        )
        sb.append("\n\n")
    }
    return sb.toString()
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

