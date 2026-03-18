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

// Create a comprehensive detekt task similar to the core project
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektPasses") {
    description = "Runs comprehensive Detekt code analysis on all source files"
    source = fileTree("$rootDir") {
        include("**/src/**")
        exclude("**/node_modules/**", "**/resources/**", "**/build/**", "**/.*", "**/carp.dsp.demo/**")
    }
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    ignoreFailures = false

}

// Auto-fix task for detekt issues
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektPassesAutoFix") {
    description = "Runs Detekt with auto-fix enabled to automatically fix formatting issues"
    source = fileTree("$rootDir") {
        include("**/src/**")
        exclude("**/node_modules/**", "**/resources/**", "**/build/**", "**/.*", "**/carp.dsp.demo/**")
    }
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    autoCorrect = true  // Enable auto-fix
    ignoreFailures = false

}

// Ensure detekt module is built before running detekt tasks
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "1.8"
    dependsOn(":detekt:assemble")
}

// Make detektPasses the default detekt task
tasks.named("detekt") {
    dependsOn("detektPasses")
}

// Configure Kover code coverage
dependencies {
    kover(project(":carp.dsp.core"))
}

// Configure Kover for comprehensive coverage reporting
kover {
    reports {
        total {
            html {
                onCheck = true
            }
            xml {
                onCheck = true
            }

            filters {
                excludes {
                    classes(
                        // Kotlin serialization generated classes (JVM binary name patterns)
                        "**$\$serializer",
                        "**\$serializer",
                        "**Companion\$serializer",

                        // Kotlin-generated companion objects from @Serializable data classes
                        // These follow the pattern ClassName$Companion in bytecode
                        "carp.dsp.core.**\$Companion",

                        // Kotlin compiler–generated synthetic helpers
                        "**\$DefaultImpls",
                        "**\$WhenMappings",
                        "**\$inlined*",
                        "**\$sam$*",

                        // data class copy$default bridge methods (JVM only)
                        "**\$copy\$default*",

                        // Test classes
                        "**Test",
                        "**Tests",
                        "**TestKt"
                    )
                    packages(
                        // Demo module — never include in core coverage
                        "carp.dsp.demo",

                    )
                    annotatedBy(
                        "carp.dsp.core.common.ExcludeFromCoverage"
                    )
                }
            }

            // Coverage verification rules
            verify {
                rule("Minimum line coverage") {
                    bound {
                        minValue = 75
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                        aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                    }
                }
                rule("Minimum branch coverage") {
                    bound {
                        minValue = 60
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
        }
    }
}

// Ensure tests run before generating coverage
tasks.named("koverHtmlReport") {
    dependsOn(":carp.dsp.core:jvmTest")
}

tasks.named("koverXmlReport") {
    dependsOn(":carp.dsp.core:jvmTest")
}


