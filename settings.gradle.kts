rootProject.name = "carp-dsp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

// ---- Local development: composite build with core ----
val corePath = file("../carp.core-kotlin")
val useLocalCore = corePath.exists() && (System.getenv("USE_LOCAL_CORE") != "false")


if (useLocalCore) {
    println("🔗 Using local composite build for carp.core-kotlin at: $corePath")
    includeBuild(corePath) {
        dependencySubstitution {
            // Map published modules to local Gradle projects.
            // Adjust this list to your actual module coordinates and paths.
            val mappings = mapOf(
                // artifactId                              -> project path in core
                "carp-core-common" to ":carp.common",
                "carp-core-data" to ":carp.data.core",
                "carp-core-analytics" to ":carp.analytics.core",
            )
            val group = "dk.cachet.carp"

            mappings.forEach { (artifact, projectPath) ->
                substitute(module("$group:$artifact")).using(project(projectPath))
            }
        }
    }
} else {
    println("📦 Falling back to published dk.cachet.carp artifacts.")
}

include(":detekt")
include("carp.dsp")

