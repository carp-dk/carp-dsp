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

