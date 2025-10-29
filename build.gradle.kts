plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
}

group = "dk.cachet.carp.dsp"
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

