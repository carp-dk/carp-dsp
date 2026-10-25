plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "carp.dsp.demo"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":carp.dsp.core"))

                // CARP dependencies
                implementation("dk.cachet.carp:carp-core-common")
                implementation("dk.cachet.carp:carp-core-data")
                implementation("dk.cachet.carp:carp-core-analytics")
                implementation("dk.cachet.carp:carp-core-protocols")

                // For coroutines support
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmMain {
            dependencies {
                // The vendored step library, to resolve `uses:` references against
                implementation(project(":carp.dsp.steps"))
                // JSON parsing for demo output
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("health.workflows:lib")
                // Ktor client for e2e server publishing
                implementation("io.ktor:ktor-client-cio:2.3.7")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

// Python working directories are not part of the published resources.
//
// `pixi.toml` and `pixi.lock` are deliberately NOT excluded: they describe the
// environment rather than being it, and they are what an environment identity would
// be computed from.
tasks.named<Copy>("jvmProcessResources") {
    exclude(
        "**/.pixi", "**/.pixi/**",
        "**/.drift-pixi", "**/.drift-pixi/**",
        "**/.drift-venvs", "**/.drift-venvs/**",
        "**/.venv", "**/.venv/**",
        "**/__pycache__", "**/__pycache__/**",
        "**/.pytest_cache", "**/.pytest_cache/**",
        "**/.ruff_cache", "**/.ruff_cache/**",
        "**/*.pyc", "**/*.pyo",
    )
}

// Create a run task that executes the JVM main class
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the CARP-DSP demo"

    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
                kotlin.jvm().compilations.getByName("main").output.allOutputs
    mainClass.set("carp.dsp.demo.DemoMainKt")

    if (project.hasProperty("args")) {
        args = (project.property("args") as String).trim().split(Regex("\\s+"))
    }

    // Required to receive input from console
    standardInput = System.`in`
    standardOutput = System.out
}

// Generic workflow runner: ./gradlew :carp.dsp.demo:runWorkflow -Pworkflow=<path> [-Pworkspace=<dir>]
tasks.register<JavaExec>("runWorkflow") {
    group = "application"
    description = "Run a DSP workflow YAML file"

    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
                kotlin.jvm().compilations.getByName("main").output.allOutputs
    mainClass.set("carp.dsp.demo.WorkflowRunnerKt")

    val workflowArg = project.findProperty("workflow") as String?
    val workspaceArg = project.findProperty("workspace") as String?

    val runArgs = mutableListOf<String>()
    if (workflowArg != null) {
        runArgs += listOf("--workflow", workflowArg)
    }
    if (workspaceArg != null) {
        runArgs += listOf("--workspace", workspaceArg)
    }
    args = runArgs

    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

// Paper evaluation harnesses (Section: Evaluation)
// ./gradlew :carp.dsp.demo:evalPlannerDeterminism [-Pargs="100"]
// ./gradlew :carp.dsp.demo:evalMobgapTiming [-Pargs="2"]
//
// Add -PpaperDir=<path to carp-dsp-sys-paper> to also copy the artefacts the paper
// uses into that repo (figures to images/, tables and macros to generated/). Without
// it, results go to eval_results/ only.
// A relative path is resolved from the directory Gradle was run in, like any CLI argument.
val paperDir: File? = (findProperty("paperDir") as String?)?.takeIf { it.isNotBlank() }?.let { path ->
    // "C:Users..." is what an unquoted C:\Users\... becomes once a POSIX shell drops the backslashes.
    if (Regex("^[A-Za-z]:[^/\\\\]").containsMatchIn(path)) {
        throw GradleException(
            "-PpaperDir=$path looks like a Windows path whose backslashes were removed by the shell. " +
                "Use forward slashes (C:/Users/...) or quote the path."
        )
    }
    File(path).takeIf { it.isAbsolute } ?: gradle.startParameter.currentDir.resolve(path)
}?.canonicalFile

fun registerEvalTask(name: String, main: String, description: String) {
    tasks.register<JavaExec>(name) {
        group = "evaluation"
        this.description = description
        classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
                    kotlin.jvm().compilations.getByName("main").output.allOutputs
        mainClass.set(main)
        if (project.hasProperty("args")) {
            args = (project.property("args") as String).trim().split(Regex("\\s+"))
        }
        paperDir?.let { systemProperty("carp.paperDir", it.absolutePath) }
        standardOutput = System.out
    }
}

registerEvalTask(
    "evalPlannerDeterminism",
    "carp.dsp.demo.eval.PlannerDeterminismEvalKt",
    "Plan 11 demo workflows N times on this machine and compare plan hashes across platforms (PL3)"
)

// PL3 on Linux, in a container: the same compiled classes and jars, run by a Linux JVM.
// Writes eval_results/planner-determinism/linux-amd64.csv next to this machine's file.
//   ./gradlew :carp.dsp.demo:evalPlannerDeterminismAll -PpaperDir=../carp-dsp-sys-paper
// runs Linux first, then this machine, which compares both and writes the macros.
val determinismJre = "eclipse-temurin:17-jre"
val determinismClasspath = tasks.register<Sync>("determinismClasspath") {
    from(kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles)
    into(layout.buildDirectory.dir("determinism-classpath"))
}
tasks.register<Exec>("evalPlannerDeterminismLinux") {
    group = "evaluation"
    description = "Run the PL3 determinism eval in a Linux container (Docker)"
    dependsOn(determinismClasspath, "jvmMainClasses", "jvmProcessResources")
    // Forward slashes: Docker accepts them for Windows paths too.
    val demo = layout.projectDirectory.asFile.absolutePath.replace("\\", "/")
    val cp = listOf(
        "/demo/build/classes/kotlin/jvm/main",
        "/demo/build/processedResources/jvm/main",
        "/demo/build/determinism-classpath/*"
    ).joinToString(":")
    commandLine(
        "docker", "run", "--rm", "-v", "$demo:/demo", "-w", "/demo", determinismJre,
        "java", "-cp", cp, "carp.dsp.demo.eval.PlannerDeterminismEvalKt"
    )
}
tasks.register("evalPlannerDeterminismAll") {
    group = "evaluation"
    description = "PL3: determinism eval in a Linux container, then on this machine"
    dependsOn("evalPlannerDeterminismLinux", "evalPlannerDeterminism")
}
tasks.named("evalPlannerDeterminism") { mustRunAfter("evalPlannerDeterminismLinux") }
registerEvalTask(
    "evalMobgapTiming",
    "carp.dsp.demo.eval.MobgapTimedEvalKt",
    "Instrumented UC1 run: phase + per-step timings, output hashes"
)
registerEvalTask(
    "evalErrorDetection",
    "carp.dsp.demo.eval.ErrorDetectionEvalKt",
    "Plan 6 fault-injected workflows and record plan-time error detection (Fig A, CARP arm)"
)
registerEvalTask(
    "evalStaticBaselines",
    "carp.dsp.demo.eval.StaticBaselineEvalKt",
    "Check the 6 injected faults with snakemake -n and cwltool --validate (PL1 baseline)"
)
registerEvalTask(
    "evalReuse",
    "carp.dsp.demo.eval.ReuseEvalKt",
    "Retarget two workflows to new data (cohort, collection period): diff, plan and execute (AU3)"
)
registerEvalTask(
    "evalStepReuse",
    "carp.dsp.demo.eval.StepReuseEvalKt",
    "Measure step reuse across 3 HR/step workflows built from a shared 6-step library (Step reuse)"
)
registerEvalTask(
    "evalPlannerScaling",
    "carp.dsp.demo.eval.PlannerScalingEvalKt",
    "Time decode/import/plan/validate over synthetic chains of 2-200 steps (Fig E)"
)
registerEvalTask(
    "evalProtocolCoupling",
    "carp.dsp.demo.eval.ProtocolCouplingEvalKt",
    "Plan a mixed protocol + open-data workflow against two study protocols (F5)"
)

// Portability eval: run the HR/step pipeline in several Linux distros (Docker) under one
// pinned Pixi env and compare output hashes. Requires Docker running + Pixi on the host.
//   ./gradlew :carp.dsp.demo:evalPortability
tasks.register("evalPortability") {
    group = "evaluation"
    description = "Cross-distro output determinism via Docker: run the HR/step pipeline in N distros and compare hashes"
    doLast {
        // Use a forward-slash relative path so bash on Windows (Git Bash) doesn't eat the
        // backslashes of an absolute Windows path.
        val result = project.exec {
            workingDir = layout.projectDirectory.asFile
            commandLine("bash", "docker/portability/run-portability.sh")
            isIgnoreExitValue = true
        }
        when (result.exitValue) {
            0 -> println("Portability: all output artifacts identical across distros.")
            1 -> println("Portability: outputs DIVERGED across distros - see eval_results/portability.txt")
            3 -> throw GradleException("Docker is not running. Start Docker Desktop / the daemon and retry.")
            else -> throw GradleException("Portability eval failed (exit ${result.exitValue}); see output above.")
        }
    }
}

// End to end: CARP arm (Kotlin) -> ad-hoc baseline (Python) -> plot, one command.
// The Python steps reuse the mobgap Pixi env CARP already provisioned under
// ~/.carp-dsp/envs/pixi (matplotlib + mobgap are both in it), so there is no env id to
// look up. Run a pipeline first (e.g. evalMobgapTiming) so that env exists.
//   ./gradlew :carp.dsp.demo:evalErrorDetectionFull
tasks.register("evalErrorDetectionFull") {
    group = "evaluation"
    description = "End to end: CARP arm, then ad-hoc baseline, then rebuild the figure"
    dependsOn("evalErrorDetection")
    doLast {
        val evalDir = layout.projectDirectory.dir("src/jvmMain/resources/scripts/eval").asFile
        val pixiEnvs = File(System.getProperty("user.home"), ".carp-dsp/envs/pixi")
        val manifest = pixiEnvs.listFiles()?.sorted()
            ?.map { File(it, "pixi.toml") }
            ?.firstOrNull { it.exists() && it.readText().contains("mobgap") }
            ?: throw GradleException(
                "No CARP mobgap Pixi env found under $pixiEnvs. Run a pipeline first " +
                "(e.g. ./gradlew :carp.dsp.demo:evalMobgapTiming) so the environment is provisioned."
            )
        fun pixi(vararg script: String) = project.exec {
            workingDir = evalDir
            commandLine(listOf("pixi", "run", "--manifest-path", manifest.absolutePath, "python") + script)
        }
        // ad-hoc arm (runs the eight mobgap steps in the provisioned env)
        pixi("adhoc_baseline.py")
        // rebuild Fig A into eval_results; with -PpaperDir also write it and the ad-hoc
        // numbers into the paper
        pixi("plot_error_detection.py")
        paperDir?.let { dir ->
            val paperFig = dir.resolve("images/fig-error-detection.pdf")
            if (!paperFig.parentFile.isDirectory) throw GradleException("Paper images/ not found: ${paperFig.parentFile}")
            pixi("plot_error_detection.py", "--out", paperFig.absolutePath)
            val numbers = layout.projectDirectory.file("eval_results/error-detection-adhoc-numbers.tex").asFile
            if (numbers.exists()) numbers.copyTo(dir.resolve("generated/error-detection-adhoc-numbers.tex"), overwrite = true)
        }
    }
}

// Fig D (CARP, per-step overhead) and Fig A (ad-hoc baseline) both report wall time of
// the same gait pipeline, so they must come from one session on one machine, or the two
// figures disagree. This runs both, in that order.
//   ./gradlew :carp.dsp.demo:evalPaperTimings -PpaperDir=../carp-dsp-sys-paper
tasks.register("evalPaperTimings") {
    group = "evaluation"
    description = "Run Fig D (evalMobgapTiming, 3 runs) and Fig A (evalErrorDetectionFull) in one session"
    dependsOn("evalMobgapTiming", "evalErrorDetectionFull")
}
tasks.named("evalErrorDetectionFull") { mustRunAfter("evalMobgapTiming") }
// Three timing runs by default when run through evalPaperTimings (run 1 installs the
// environment, so the figure needs at least two more); -Pargs still overrides.
if (!project.hasProperty("args") && gradle.startParameter.taskNames.any { it.endsWith("evalPaperTimings") }) {
    tasks.named<JavaExec>("evalMobgapTiming") { args = listOf("3") }
}

