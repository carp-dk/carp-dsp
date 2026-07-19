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

                // For coroutines support
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmMain {
            dependencies {
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
}

// Paper evaluation harnesses (Section: Evaluation)
// ./gradlew :carp.dsp.demo:evalPlannerDeterminism [-Pargs="100"]
// ./gradlew :carp.dsp.demo:evalMobgapTiming [-Pargs="2"]
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
        standardOutput = System.out
    }
}

registerEvalTask(
    "evalPlannerDeterminism",
    "carp.dsp.demo.eval.PlannerDeterminismEvalKt",
    "Plan the mobgap workflow N times and verify plan determinism"
)
registerEvalTask(
    "evalMobgapTiming",
    "carp.dsp.demo.eval.MobgapTimedEvalKt",
    "Instrumented UC1 run: phase + per-step timings, output hashes"
)
registerEvalTask(
    "evalErrorDetection",
    "carp.dsp.demo.eval.ErrorDetectionEvalKt",
    "Plan 5 fault-injected workflows and record plan-time error detection (Fig A, CARP arm)"
)
registerEvalTask(
    "evalReuse",
    "carp.dsp.demo.eval.ReuseEvalKt",
    "Compare the MS and HA cohort workflows: authoring diff + planner acceptance (Use Case 2 / Reuse)"
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
        // rebuild Fig A; drop it straight into the paper repo if it sits beside this one
        val paperFig = rootProject.projectDir.parentFile.resolve("carp-dsp-sys-paper/images/fig-error-detection.pdf")
        if (paperFig.parentFile.exists()) pixi("plot_error_detection.py", "--out", paperFig.absolutePath)
        else pixi("plot_error_detection.py")
    }
}

