plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
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
                // JVM-specific dependencies if needed
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

// Create a test task for P0 demo
tasks.register<JavaExec>("testP0Demo") {
    group = "verification"
    description = "Test the P0 Planning Demo"

    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
                kotlin.jvm().compilations.getByName("main").output.allOutputs
    mainClass.set("carp.dsp.demo.TestP0DemoKt")

    standardOutput = System.out
}

