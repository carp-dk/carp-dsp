plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "carp.dsp"
version = project.property("version") as String

// Configure Detekt code analysis
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/detekt.yml")
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
    detektPlugins(project(":detekt"))
}

// Create a detekt task that runs on all source files
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektAll") {
    description = "Runs Detekt code analysis on all modules"
    parallel = true
    setSource(files(projectDir))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/resources/**", "**/.*")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true

    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}

// Ensure detekt module is built before running detekt tasks
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "1.8"
    dependsOn(":detekt:assemble")
}

// Configure Kover code coverage
kover {
    reports {
        // Configure report outputs
        filters {
            // Exclude test code from coverage
            excludes {
                classes(
                    "*Test",
                    "*Test\$*",
                    "*Tests",
                    "*Spec"
                )
                packages(
                    "*.test",
                    "*.test.*"
                )
            }
        }
    }
}

// Configure coverage verification with thresholds
koverReport {
    defaults {
        // Generate HTML report (human-readable)
        html {
            onCheck = true
            htmlDir = layout.buildDirectory.dir("reports/kover/html")
        }

        // Generate XML report (for CI/CD integration)
        xml {
            onCheck = true
            xmlFile = layout.buildDirectory.file("reports/kover/coverage.xml")
        }

        // Console output for quick checks
        log {
            onCheck = true
        }

        // Enforce minimum coverage thresholds
        verify {
            onCheck = true

            rule {
                // Overall project coverage minimum
                minBound(70)
            }

            rule("Class coverage") {
                // Per-class minimum coverage
                entity = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.CLASS
                minBound(60)
            }
        }
    }
}
