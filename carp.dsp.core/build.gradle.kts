plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    kotlin("plugin.serialization") version "2.1.20"
}

group = "carp.dsp.core"
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
                implementation("dk.cachet.carp:carp-core-common")
                implementation("dk.cachet.carp:carp-core-data")
                implementation("dk.cachet.carp:carp-core-analytics")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

                // For coroutines support
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

                // Ktor HTTP client for data retrieval
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }

        jvmMain {
            dependencies {
                // Ktor CIO engine for JVM
                implementation("io.ktor:ktor-client-cio:2.3.7")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }
    }
}
