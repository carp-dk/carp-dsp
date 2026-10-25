package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.resolve.UsesResolver
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.io.DemoIo
import carp.dsp.steps.ClasspathStepLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * PL1 baseline: do Snakemake and CWL catch the same six faults before anything runs?
 *
 * Writes the wiring of the two clean workflows and the six fault-injected ones (the same
 * files [ErrorDetectionEval] plans) as JSON. `static_baselines.py` turns each into a
 * Snakefile and a CWL Workflow, runs `snakemake -n` and `cwltool --validate`, and writes
 * `static-baselines.csv` and `static-baselines-numbers.tex`. The macros go to the paper
 * when `-PpaperDir` is set.
 *
 * The script runs in Docker ([PIXI_IMAGE]) against the pinned `scripts/eval/baselines/pixi.lock`,
 * so every OS gets the same tool versions. Without Docker it falls back to a local pixi
 * (Linux or macOS only; cwltool does not run on Windows).
 */
fun main() {
    val codec = WorkflowYamlCodec()
    val library = ClasspathStepLibrary()
    val outDir = DemoIo.evalResultsDir().resolve("static-baselines").apply { mkdirs() }
    val wiringDir = outDir.resolve("wiring").apply { mkdirs() }

    val cases = INJECTIONS.map { it.base }.distinct().map { "clean-" + workflowId(it) to it.resource } +
        INJECTIONS.map { it.fault to it.resource }
    cases.forEach { (case, resource) ->
        val descriptor = UsesResolver(library).resolve(codec.decodeOrThrow(DemoIo.loadResource(resource))).workflow
        wiringDir.resolve("$case.json").writeText(JSON.encodeToString(JsonObject.serializer(), wiring(case, descriptor)))
    }
    println("Wrote ${cases.size} wiring files to ${wiringDir.absolutePath}")

    val csv = outDir.resolve("static-baselines.csv")
    val numbers = outDir.resolve("static-baselines-numbers.tex")
    if (runBaselines(outDir) && numbers.exists()) {
        println(csv.readText())
        DemoIo.publishToPaper(numbers, "generated/static-baselines-numbers.tex")
    }
}

private val JSON = Json { prettyPrint = true }

private fun workflowId(base: BaseWorkflow): String = base.resource.substringAfterLast('/').removeSuffix(".yaml")

/** The tool-neutral wiring the generators need: steps, environments, ports, formats and data types. */
private fun wiring(case: String, wf: WorkflowDescriptor): JsonObject = buildJsonObject {
    put("case", case)
    put("environments", buildJsonObject { wf.environments.forEach { (id, env) -> put(id, env.kind) } })
    put(
        "steps",
        buildJsonArray {
            wf.steps.filterIsInstance<DefinedStepDescriptor>().forEach { step ->
                add(
                    buildJsonObject {
                        put("id", step.id ?: step.task.name)
                        put("environmentId", step.environmentId)
                        put("inputs", buildJsonArray { step.inputs.forEach { add(port(it, withSource = true)) } })
                        put("outputs", buildJsonArray { step.outputs.forEach { add(port(it, withSource = false)) } })
                    }
                )
            }
        }
    )
}

private fun port(p: DataPortDescriptor, withSource: Boolean): JsonObject = buildJsonObject {
    put("id", p.id ?: "")
    put("format", p.descriptor?.fileFormat ?: "")
    put(
        "dataTypes",
        buildJsonArray {
            p.descriptor?.fields.orEmpty().mapNotNull { it.dataType }.distinct()
                .forEach { add(JsonPrimitive("${it.namespace}.${it.name}")) }
        }
    )
    if (withSource) {
        put(
            "source",
            when (val s = p.source) {
                is StepOutputInputSource -> buildJsonObject {
                    put("kind", "step")
                    put("stepId", s.stepId)
                    put("outputId", s.outputId)
                }
                is FileInputSource -> buildJsonObject {
                    put("kind", "file")
                    put("path", s.path)
                }
                else -> buildJsonObject { put("kind", "other") }
            }
        )
    }
}

/** Pinned so the pixi that reads `pixi.lock` never changes under the eval. */
private const val PIXI_IMAGE = "ghcr.io/prefix-dev/pixi:0.83.0"

/** Named volume for the environment: cached between runs, and kept off a Windows bind mount. */
private const val PIXI_ENV_VOLUME = "carp-dsp-static-baselines-pixi"

/** Runs `static_baselines.py` on the wiring in [outDir]; true when it exits 0. */
private fun runBaselines(outDir: File): Boolean {
    val dir = DemoIo.projectRoot().resolve("src/jvmMain/resources/scripts/eval/baselines")
    val task = listOf("pixi", "run", "--locked", "static-baselines")
    val command = when {
        dockerRunning() -> listOf(
            "docker", "run", "--rm",
            "-v", "${hostPath(dir)}:/baselines",
            "-v", "$PIXI_ENV_VOLUME:/baselines/.pixi",
            "-v", "${hostPath(outDir)}:/out",
            "-w", "/baselines",
            PIXI_IMAGE
        ) + task + scriptArgs("/out")
        isWindows() -> {
            System.err.println("Docker is not running. Start Docker Desktop and retry (cwltool does not run on Windows).")
            return false
        }
        onPath("pixi") -> task + scriptArgs(outDir.absolutePath)
        else -> {
            System.err.println("Neither Docker nor pixi found. Start Docker, or install pixi.")
            return false
        }
    }
    println("Running: ${command.joinToString(" ")}")
    val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().forEachLine { println(it) }
    val code = process.waitFor()
    if (code != 0) System.err.println("static_baselines.py exited with code $code")
    return code == 0
}

private fun scriptArgs(out: String): List<String> = listOf(
    "--wiring", "$out/wiring",
    "--work", "$out/generated",
    "--csv", "$out/static-baselines.csv",
    "--tex", "$out/static-baselines-numbers.tex"
)

/** Forward slashes, which Docker accepts for Windows paths too. */
private fun hostPath(f: File): String = f.absolutePath.replace("\\", "/")

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

private fun dockerRunning(): Boolean = succeeds("docker", "info")

private fun onPath(exe: String): Boolean = succeeds(exe, "--version")

/** True if [command] starts and exits 0. */
@Suppress("TooGenericExceptionCaught")
private fun succeeds(vararg command: String): Boolean =
    try {
        ProcessBuilder(*command).redirectErrorStream(true).start().also { p ->
            p.inputStream.readAllBytes() // drain, so a chatty command cannot block
        }.waitFor() == 0
    } catch (_: Exception) {
        // Not installed or not on PATH; the caller tries the next option.
        false
    }
