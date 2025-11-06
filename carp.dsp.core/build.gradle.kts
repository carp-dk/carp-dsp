plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
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
