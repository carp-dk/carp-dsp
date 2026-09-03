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

// ---- Local development: composite build with carp.core-kotlin ----

val verboseCoreSetup = (extra.properties["coreSetupVerbose"] as String?)?.toBoolean() ?: false

fun trace(message: String) {
    if (verboseCoreSetup) println(message)
}

val possibleCorePaths = listOf(
    file("../carp.core-kotlin"),  // sibling checkout - the normal case
    file("../carp"),              // alternative sibling name
    file("carp.core-kotlin"),     // nested checkout
)

fun looksLikeCore(path: File): Boolean =
    path.isDirectory &&
        (File(path, "settings.gradle.kts").exists() || File(path, "build.gradle.kts").exists())

trace("carp-dsp: looking for carp.core-kotlin (USE_LOCAL_CORE=${System.getenv("USE_LOCAL_CORE")})")
possibleCorePaths.forEach {
    trace("  ${it.absolutePath} -> ${if (looksLikeCore(it)) "usable" else "not found"}")
}

val corePath = possibleCorePaths.firstOrNull(::looksLikeCore)

if (corePath != null && System.getenv("USE_LOCAL_CORE") != "false") {
    // A directory that looks like the repo but has no settings file is a partial
    // clone, and the failure it causes later is unrecognisable. Say so here.
    if (!File(corePath, "settings.gradle.kts").exists()) {
        throw GradleException(
            """
            carp.core-kotlin at ${corePath.absolutePath} has no settings.gradle.kts.

            The checkout looks incomplete. Check that it cloned fully and that it
            is on the branch this build expects (feature/core-analytics).
            """.trimIndent()
        )
    }

    println("carp-dsp: carp.core-kotlin from ${corePath.absolutePath}")

    includeBuild(corePath) {
        dependencySubstitution {
            mapOf(
                "carp-core-common" to ":carp.common",
                "carp-core-data" to ":carp.data.core",
                "carp-core-analytics" to ":carp.analytics.core",
                "carp-core-protocols" to ":carp.protocols.core",
                "carp-core-studies" to ":carp.studies.core",
                "carp-core-deployments" to ":carp.deployments.core",
            ).forEach { (artifact, projectPath) ->
                substitute(module("dk.cachet.carp:$artifact")).using(project(projectPath))
                trace("  dk.cachet.carp:$artifact -> $projectPath")
            }
        }
    }
} else {
    val why = if (corePath == null) "no local checkout found" else "USE_LOCAL_CORE=false"
    println("carp-dsp: carp.core-kotlin from Maven Central ($why)")
    possibleCorePaths.forEach { trace("  looked in ${it.absolutePath}") }
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

// Step library. Vendored by default; a "minimal" install skips it and
// resolves every `uses:` reference through the registry instead.
//   ./gradlew build -PcarpDspSteps=false
// or set carpDspSteps=false in gradle.properties.
val includeStepLibrary = (extra.properties["carpDspSteps"] as String?)?.toBoolean() ?: true
if (includeStepLibrary) {
    include(":carp.dsp.steps")
} else {
    println("Step library excluded (carpDspSteps=false) - steps resolve through the registry")
}

