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
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
    detektPlugins(project(":detekt"))
}
