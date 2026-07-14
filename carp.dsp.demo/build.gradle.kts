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

    // Allow passing arguments to the demo
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split("\\s+")
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

