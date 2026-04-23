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
    try {
        val exists = path.exists()
        val absolutePath = path.absolutePath
        println("  ${index + 1}. $absolutePath -> ${if (exists) "✅ EXISTS" else "❌ NOT FOUND"}")
        if (exists && path.isDirectory) {
            val settingsFile = File(path, "settings.gradle.kts")
            val buildFile = File(path, "build.gradle.kts")
            println("     - settings.gradle.kts: ${if (settingsFile.exists()) "✅" else "❌"}")
            println("     - build.gradle.kts: ${if (buildFile.exists()) "✅" else "❌"}")

            // Check if this looks like a valid CARP core repository
            if (settingsFile.exists() || buildFile.exists()) {
                println("     - Valid CARP core structure detected")
            }
        }
    } catch (e: Exception) {
        println("  ${index + 1}. ${path.path} -> ❌ ERROR: ${e.message}")
    }
}

// Find the first valid CARP core path
val corePath = possibleCorePaths.firstOrNull { path ->
    try {
        path.exists() && path.isDirectory && (
            File(path, "settings.gradle.kts").exists() ||
            File(path, "build.gradle.kts").exists()
        )
    } catch (e: Exception) {
        println("⚠️ Error checking path ${path.absolutePath}: ${e.message}")
        false
    }
}
val useLocalCore = corePath != null && (System.getenv("USE_LOCAL_CORE") != "false")

if (useLocalCore && corePath != null) {
    println("🔗 Using local composite build for carp.core-kotlin")
    println("  - Path: ${corePath.absolutePath}")
    println("  - Canonical path: ${corePath.canonicalPath}")

    try {
        // Verify the core project structure
        val coreSettingsFile = File(corePath, "settings.gradle.kts")
        if (!coreSettingsFile.exists()) {
            val errorMsg = """
                ❌ CARP CORE BUILD FAILURE: Missing settings.gradle.kts
                
                Expected: ${coreSettingsFile.absolutePath}
                
                The CARP core repository at '${corePath.absolutePath}' appears to be incomplete.
                Please ensure:
                1. The repository is properly cloned
                2. It's on the correct branch (feature/core-analytics)
                3. The settings.gradle.kts file exists
                
                This is a failure in the dependent repository (carp.core-kotlin), not carp-dsp.
            """.trimIndent()

            println(errorMsg)
            throw GradleException(errorMsg)
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
                // TODO: Make the not found to not found continuing
    } catch (e: GradleException) {
        // Re-throw GradleExceptions as-is
        throw e
    } catch (e: Exception) {
        val errorMessage = """
            ❌ CARP CORE BUILD FAILURE: Composite build setup failed
            
            Error: ${e.message ?: "Unknown error"}
            Error type: ${e::class.simpleName}
            CARP core path: ${corePath.absolutePath}
            
            This is a failure in the dependent repository (carp.core-kotlin), not carp-dsp.
            
            Troubleshooting steps:
            1. Verify CARP core is on branch: feature/core-analytics
            2. Ensure CARP core builds independently: cd '${corePath.absolutePath}' && ./gradlew build
            3. Check CARP core project structure and Gradle files
            4. Verify the CARP core repository was cloned completely
        """.trimIndent()

        println(errorMessage)
        throw GradleException(errorMessage, e)
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

// ---- Composite build: health-workflow-interfaces ----
// Required in all environments — not published to Maven Central.
// Locally: sibling directory ../health-workflow-interfaces
// CI: checked out to the same relative path by ci.yml
val hwifPath = file("../health-workflow-interfaces")
if (!hwifPath.exists()) {
    throw GradleException(
        """
        health-workflow-interfaces not found at ${hwifPath.absolutePath}

        This library is not published to Maven Central and must be available
        as a local composite build.

        Locally:  clone health-workflow-interfaces as a sibling of carp-dsp
        CI:       the checkout step in ci.yml must check it out to health-workflow-interfaces/
        """.trimIndent()
    )
}
includeBuild(hwifPath) {
    dependencySubstitution {
        substitute(module("health.workflows:lib")).using(project(":lib"))
    }
}

include(":detekt")
include(":carp.dsp.core")
include(":carp.dsp.demo")

