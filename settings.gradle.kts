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
println("🔍 Environment detection:")
println("  - Current directory: ${rootDir.absolutePath}")
println("  - USE_LOCAL_CORE env: ${System.getenv("USE_LOCAL_CORE")}")
println("  - CI environment: ${System.getenv("CI") ?: "false"}")

// Check multiple possible paths for CARP core
val possibleCorePaths = listOf(
    file("../carp.core-kotlin"),           // Local development (parent/carp.core-kotlin)
    file("../carp"),                       // Alternative sibling structure
    file("./carp.core-kotlin"),           // Same directory
    file("carp.core-kotlin")              // Subdirectory
)

println("🔍 Checking for CARP core in possible locations:")
possibleCorePaths.forEachIndexed { index, path ->
    val exists = path.exists()
    val absolutePath = path.absolutePath
    println("  ${index + 1}. $absolutePath -> ${if (exists) "✅ EXISTS" else "❌ NOT FOUND"}")
    if (exists && path.isDirectory) {
        val settingsFile = File(path, "settings.gradle.kts")
        val buildFile = File(path, "build.gradle.kts")
        println("     - settings.gradle.kts: ${if (settingsFile.exists()) "✅" else "❌"}")
        println("     - build.gradle.kts: ${if (buildFile.exists()) "✅" else "❌"}")
    }
}

val corePath = possibleCorePaths.firstOrNull { it.exists() && it.isDirectory }
val useLocalCore = corePath != null && (System.getenv("USE_LOCAL_CORE") != "false")

if (useLocalCore && corePath != null) {
    println("🔗 Using local composite build for carp.core-kotlin")
    println("  - Path: ${corePath.absolutePath}")
    println("  - Canonical path: ${corePath.canonicalPath}")

    try {
        // Verify the core project structure
        val coreSettingsFile = File(corePath, "settings.gradle.kts")
        if (!coreSettingsFile.exists()) {
            throw GradleException("""
                ❌ CARP CORE BUILD FAILURE: Missing settings.gradle.kts
                
                Expected: ${coreSettingsFile.absolutePath}
                
                The CARP core repository at '${corePath.absolutePath}' appears to be incomplete.
                Please ensure:
                1. The repository is properly cloned
                2. It's on the correct branch (feature/core-analytics)
                3. The settings.gradle.kts file exists
                
                This is a failure in the dependent repository (carp.core-kotlin), not carp-dsp.
            """.trimIndent())
        }

        println("✅ CARP core structure validation passed")

        includeBuild(corePath) {
            dependencySubstitution {
                println("🔄 Setting up dependency substitution for CARP core modules...")

                // Map published modules to local Gradle projects.
                val mappings = mapOf(
                    "carp-core-common" to ":carp.common",
                    "carp-core-data" to ":carp.data.core",
                    "carp-core-analytics" to ":carp.analytics.core",
                )
                val group = "dk.cachet.carp"

                mappings.forEach { (artifact, projectPath) ->
                    substitute(module("$group:$artifact")).using(project(projectPath))
                    println("  - $group:$artifact -> $projectPath")
                }

                println("✅ Dependency substitution configured successfully")
            }
        }

        println("✅ CARP core composite build setup completed successfully")

    } catch (Exception e) {
        val errorMessage = """
            ❌ CARP CORE BUILD FAILURE: Composite build setup failed
            
            Error: ${e.message}
            CARP core path: ${corePath.absolutePath}
            
            This is a failure in the dependent repository (carp.core-kotlin), not carp-dsp.
            
            Troubleshooting steps:
            1. Verify CARP core is on branch: feature/core-analytics
            2. Ensure CARP core builds independently: cd ${corePath.absolutePath} && ./gradlew build
            3. Check CARP core project structure and Gradle files
            
            Stack trace: ${e.stackTrace.take(5).joinToString("\n") { "  at $it" }}
        """.trimIndent()

        println(errorMessage)
        throw GradleException(errorMessage)
    }

} else {
    if (corePath == null) {
        println("📦 No local CARP core found - falling back to published artifacts")
        println("  Searched paths:")
        possibleCorePaths.forEach { path ->
            println("    - ${path.absolutePath}")
        }
    } else {
        println("📦 USE_LOCAL_CORE disabled - falling back to published artifacts")
    }
    println("📦 Using published dk.cachet.carp artifacts from Maven Central")
}

include(":detekt")
include(":carp.dsp.core")
include(":carp.dsp.demo")

