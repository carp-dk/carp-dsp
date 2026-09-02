import java.security.MessageDigest
import java.time.LocalDate

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlin.serialization)
}

group = "carp.dsp.steps"
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
                implementation(project(":carp.dsp.core"))
                implementation("dk.cachet.carp:carp-core-common")
                implementation("dk.cachet.carp:carp-core-data")
                implementation("dk.cachet.carp:carp-core-analytics")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmTest {
            dependencies {
                implementation(project(":carp.dsp.core"))
                implementation(libs.kaml)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.0")
            }
        }
    }
}

// This deliberately mirrors `StepContentHash.TRANSIENT`, which excludes the same
// artefacts from a step's content hash: what is not part of a step's identity is not
// part of its package either. Keep the two lists in step.
tasks.named<Copy>("jvmProcessResources") {
    exclude(
        "**/__pycache__", "**/__pycache__/**",
        "**/.pytest_cache", "**/.pytest_cache/**",
        "**/.ruff_cache", "**/.ruff_cache/**",
        "**/.ipynb_checkpoints", "**/.ipynb_checkpoints/**",
        "**/*.pyc", "**/*.pyo",
    )
}

// ── Library conformance check ──────────────────────────────────────────────────
//
// The automated half of contribution review, running as ordinary tests so
// `build` and CI enforce it without extra wiring:
//   - every step.yaml decodes, lints and plans with zero ERROR issues
//   - declared ports carry file formats, format terms and ontology terms
//   - discovery metadata is complete
//   - naming and tier placement are valid, checked against declared data types
//   - every declared implementation and reference fixture is present
//   - the published content hash still matches what the step ships
//
// What is still NOT verified is that a step reproduces its fixture in the
// environment it *declares*: the tests run against whatever interpreter is on PATH.
tasks.register("validateStepLibrary") {
    group = "verification"
    description = "Runs the step library conformance check"
    dependsOn("jvmTest")
}

// ── Step scaffolding ──────────────────────────────────────────────────────────
//
// Create a new step from a language template rather than copying by hand:
//   ./gradlew :carp.dsp.steps:newStep -Pid=sensing.heartrate.hrv-rmssd -Planguage=python
//
// `id` is <tier>.<subject>.<step>; `language` is python|r|kotlin (default python).
// The task stamps templates/<language>/ into resources/steps/<tier>/<subject>/<step>/
// with tier, subject, name and ids filled in. It never creates a certification
// record - that is added by a maintainer in the reviewing pull request - and it
// refuses to overwrite an existing step.
tasks.register("newStep") {
    group = "step library"
    description = "Scaffold a new step from a language template (-Pid=<tier>.<subject>.<name> [-Planguage=python])"

    doLast {
        val id = (project.findProperty("id") as String?)
            ?: error("Missing -Pid. Example: -Pid=sensing.heartrate.hrv-rmssd")
        val language = (project.findProperty("language") as String?) ?: "python"

        val parts = id.split('.')
        require(parts.size == 3 && parts.all { it.isNotBlank() }) {
            "id must be <tier>.<subject>.<step>, got '$id'"
        }
        val (tier, subject, step) = parts
        require(tier in setOf("core", "sensing", "analysis")) {
            "tier must be one of core, sensing, analysis (got '$tier')"
        }
        require(Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(step)) {
            "step name must be lowercase words separated by hyphens (got '$step')"
        }

        val templateDir = layout.projectDirectory.dir("templates/$language").asFile
        require(templateDir.isDirectory) {
            "No template for language '$language' at $templateDir"
        }

        val target = layout.projectDirectory
            .dir("src/jvmMain/resources/steps/$tier/$subject/$step").asFile
        require(!target.exists()) { "Step already exists at $target" }

        // A readable name derived from the step slug: "hrv-rmssd" -> "Hrv rmssd".
        val name = step.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val tokens = mapOf(
            "{{ID}}" to id,
            "{{TIER}}" to tier,
            "{{SUBJECT}}" to subject,
            "{{STEP}}" to step,
            "{{NAME}}" to name,
            "{{TASK_ID}}" to id.replace('.', '-'),
            "{{STEP_MODULE}}" to step.replace('-', '_'),
        )

        templateDir.walkTopDown().filter { it.isFile && it.name != ".gitkeep" }.forEach { source ->
            val relative = source.relativeTo(templateDir).path
            // Files named after the step carry the slug in their final name.
            val renamed = relative
                .replace("impl/python/step.py", "impl/python/$step.py")
                .replace("impl/python/test_step.py", "impl/python/test_$step.py")
            val destination = target.resolve(renamed)
            destination.parentFile.mkdirs()
            destination.writeText(tokens.entries.fold(source.readText()) { text, (k, v) ->
                text.replace(k, v)
            })
        }

        // reference/ is created empty: the fixture must be generated by running
        // the implementation, never scaffolded.
        target.resolve("reference").mkdirs()

        logger.lifecycle("Created step '$id' at ${target.relativeTo(rootDir)}")
        logger.lifecycle("Next: implement compute(), generate reference/, then ./gradlew :carp.dsp.steps:validateStepLibrary")
    }
}

// ── Step directory helpers ────────────────────────────────────────────────────

/** Every published step directory, i.e. every directory holding a `step.yaml`. */
fun stepDirectories(): List<File> =
    layout.projectDirectory.dir("src/jvmMain/resources/steps").asFile
        .walkTopDown().filter { it.name == "step.yaml" }.map { it.parentFile }
        .sortedBy { it.invariantSeparatorsPath }.toList()

fun stepIdOf(dir: File): String =
    dir.relativeTo(layout.projectDirectory.dir("src/jvmMain/resources/steps").asFile)
        .invariantSeparatorsPath.replace('/', '.')

/** Left behind by running a step's tests; produced by working with a step, not published by it. */
val transientArtefacts = setOf("__pycache__", ".pytest_cache", ".ruff_cache", ".ipynb_checkpoints")

fun File.isPublishedContentOf(root: File): Boolean {
    if (!isFile || name == "certification.yaml") return false
    if (extension in setOf("pyc", "pyo")) return false
    return relativeTo(root).invariantSeparatorsPath.split('/').none { it in transientArtefacts }
}

/**
 * Canonical content hash of a published step: SHA-256 over every file it ships,
 * ordered by path, each contributing its path then its bytes.
 *
 * `certification.yaml` is excluded because it is where the hash is recorded, and
 * test caches because otherwise running a step's tests would invalidate the very
 * certification they support.
 *
 * Mirrors `StepContentHash` in carp.dsp.core; kept in the build script so the task
 * does not need the module on its buildscript classpath.
 */
fun contentHashOf(dir: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    dir.walkTopDown()
        .filter { it.isPublishedContentOf(dir) }
        .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray())
            digest.update(file.readBytes())
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ── Certification records ─────────────────────────────────────────────────────
//
// Publish the content hash each step is pinned against:
//   ./gradlew :carp.dsp.steps:certifySteps
//
// A consumer's steps.lock pins a resolution by this hash, so it is published with
// the step rather than recomputed by each consumer - a jar cannot be walked the
// way a directory can, so without a published value the two library
// implementations could not agree. Re-run after changing anything a step ships.
tasks.register("certifySteps") {
    group = "step library"
    description = "Recompute and write the content hash into each step's certification.yaml"

    doLast {
        var changed = 0
        stepDirectories().forEach { dir ->
            val id = stepIdOf(dir)
            val hash = contentHashOf(dir)
            val file = dir.resolve("certification.yaml")
            val version = Regex("""^version:\s*"?([^"\n]+)"?""", RegexOption.MULTILINE)
                .find(file.takeIf { it.isFile }?.readText().orEmpty())?.groupValues?.get(1)
                ?: Regex("""^\s{2}version:\s*"([^"]+)"""", RegexOption.MULTILINE)
                    .find(dir.resolve("step.yaml").readText())?.groupValues?.get(1)
                ?: "1.0"

            val existing = file.takeIf { it.isFile }?.readText().orEmpty()
            val previous = Regex("""^contentHash:\s*"?([^"\n]+)"?""", RegexOption.MULTILINE)
                .find(existing)?.groupValues?.get(1)?.takeIf { it != "null" }

            fun field(name: String) = Regex("""^$name:\s*"?([^"\n]+)"?""", RegexOption.MULTILINE)
                .find(existing)?.groupValues?.get(1)?.takeIf { it != "null" }

            val level = field("level") ?: "gated"
            val reviewedOn = field("reviewedOn")
            val reviewer = field("reviewer")
            val reviewedPr = field("reviewedPr")
            val reviewedHash = field("reviewedHash")

            file.writeText(
                buildString {
                    appendLine("""id: "$id"""")
                    appendLine("""version: "$version"""")
                    appendLine("""level: "$level"""")
                    appendLine("""contentHash: "$hash"""")
                    appendLine(reviewedOn?.let { """reviewedOn: "$it"""" } ?: "reviewedOn: null")
                    appendLine(reviewer?.let { """reviewer: "$it"""" } ?: "reviewer: null")
                    appendLine(reviewedPr?.let { """reviewedPr: "$it"""" } ?: "reviewedPr: null")
                    appendLine(reviewedHash?.let { """reviewedHash: "$it"""" } ?: "reviewedHash: null")
                }
            )
            if (previous != hash) {
                changed++
                logger.lifecycle("  $id  ${previous?.take(12) ?: "(none)"} -> ${hash.take(12)}")
            }
        }
        logger.lifecycle("Certified ${stepDirectories().size} step(s), $changed updated.")
        if (changed > 0) {
            logger.lifecycle("Content changed: any steps.lock pinning these steps must be refreshed.")
        }
    }
}

// ── Step implementation tests ─────────────────────────────────────────────────
//
// Runs each step's own test suite:
//   ./gradlew :carp.dsp.steps:verifyStepImplementations
//   ./gradlew :carp.dsp.steps:verifyStepImplementations -Ppython=/path/to/python
//
// The conformance gate checks a step's contract, its files and its hash, but
// says nothing about whether the implementation still computes the right answer.
// That question needs the code run, which needs an interpreter and the step's
// dependencies, so it is a separate task rather than part of `build`.
//
// Tests rather than a fixture diff: a fixture shows a step was validated once and
// only suits a step with a fixed input, while a test says what correct means -
// including for a network loader (exercised against a local archive) and a
// generator (checked for determinism and schema).
//
// Skips cleanly when the interpreter or pytest is missing, so it does not fail a
// machine that has not been set up to run it. Verifying against each step's own
// declared environment rather than the ambient interpreter is future work.
tasks.register("verifyStepImplementations") {
    group = "verification"
    description = "Runs each library step's implementation tests (needs python + pytest)"

    doLast {
        val python = (project.findProperty("python") as String?) ?: "python"

        val available = runCatching {
            providers.exec {
                commandLine(python, "-c", "import pytest")
                isIgnoreExitValue = true
            }.result.get().exitValue == 0
        }.getOrDefault(false)

        if (!available) {
            logger.lifecycle("Skipping: '$python' with pytest was not found.")
            logger.lifecycle("Install pytest, or point at another interpreter with -Ppython=<path>.")
            return@doLast
        }

        val failures = mutableListOf<String>()
        stepDirectories().forEach { dir ->
            val id = stepIdOf(dir)
            val hasTests = dir.walkTopDown().any { it.isFile && it.name.startsWith("test_") }
            if (!hasTests) {
                logger.lifecycle("  $id: no tests")
                return@forEach
            }
            val result = providers.exec {
                workingDir = dir
                commandLine(python, "-m", "pytest", "impl", "-q")
                isIgnoreExitValue = true
            }.result.get()
            if (result.exitValue == 0) {
                logger.lifecycle("  PASS  $id")
            } else {
                logger.lifecycle("  FAIL  $id")
                failures += id
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Step implementation tests failed: ${failures.joinToString(", ")}. " +
                    "Run them directly for the detail: cd <step dir> && $python -m pytest impl -q"
            )
        }
        logger.lifecycle("All step implementation tests passed.")
    }
}

// ── Environment catalogue sync ────────────────────────────────────────────────
//
// The environment catalogue in resources/environments/ is the source of truth.
// During generation, the matching catalogue entry is inlined so published steps
// remain self-contained.
//
// To update the inlined environment definitions, run:
//
//   ./gradlew :carp.dsp.steps:syncStepEnvironments
//
// Avoid editing the inlined blocks manually. The environment referenced by
// library.environment.default is copied from the catalogue, with its id and
// description omitted because the key provides the identity and the catalogue
// owns the description.
tasks.register("syncStepEnvironments") {
    group = "step library"
    description = "Regenerate each step's inlined environment from the environment catalogue"

    doLast {
        val catalogue = layout.projectDirectory.dir("src/jvmMain/resources/environments").asFile
        var changed = 0

        stepDirectories().forEach { dir ->
            val id = stepIdOf(dir)
            val file = dir.resolve("step.yaml")
            val text = file.readText()

            val envId = Regex("""^\s{4}default:\s*"([^"]+)"""", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)
                ?: run { logger.warn("  $id: no library.environment.default - skipped"); return@forEach }

            val source = catalogue.resolve("$envId.yaml")
            if (!source.isFile) {
                logger.warn("  $id: '$envId' is not in the catalogue - skipped")
                return@forEach
            }

            // Catalogue entry -> inlined block: drop identity/description lines,
            // indent the rest under `environments: <envId>:`.
            val body = source.readText().lines()
                .dropWhile { it.isBlank() }
                .filterNot { it.startsWith("id:") || it.startsWith("description:") }
                .filter { it.isNotBlank() }
                .joinToString("\n") { "    $it" }
            val block = "environments:\n  $envId:\n$body"

            val lines = text.lines()
            val start = lines.indexOfFirst { it.startsWith("environments:") }
            if (start < 0) { logger.warn("  $id: no environments block - skipped"); return@forEach }
            val end = lines.drop(start + 1)
                .indexOfFirst { it.isNotBlank() && !it.first().isWhitespace() }
                .let { if (it < 0) lines.size else start + 1 + it }

            val updated = (lines.take(start) + block.lines() + "" + lines.drop(end)).joinToString("\n")
            if (updated != text) {
                file.writeText(updated)
                changed++
                logger.lifecycle("  $id: environment '$envId' resynced")
            }
        }
        logger.lifecycle("Checked ${stepDirectories().size} step(s), $changed updated.")
        if (changed > 0) logger.lifecycle("Run certifySteps to refresh content hashes.")
    }
}

// ── Coverage ──────────────────────────────────────────────────────────────────
//
// Step implementations are small, self-contained transformations with little
// untestable orchestration, so the library holds a higher floor than the
// framework's 75/60. Reported separately rather than merged into the root total,
// which would blend two different standards into one number.
// TODO: enable with the first merged step. Verification is deliberately left off
// while the module has no sources - an empty module has no covered lines, so a
// percentage bound would fail the build on nothing. The agreed floors are 85%
// line and 70% branch; uncomment the block below once a step exists.
//
// kover {
//     reports {
//         total {
//             verify {
//                 rule("Minimum line coverage (step library)") {
//                     bound {
//                         minValue = 85
//                         coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
//                         aggregationForGroup =
//                             kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
//                     }
//                 }
//                 rule("Minimum branch coverage (step library)") {
//                     bound {
//                         minValue = 70
//                         coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
//                         aggregationForGroup =
//                             kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
//                     }
//                 }
//             }
//         }
//     }
// }

// ── Recording a review ────────────────────────────────────────────────────────
//
// Promotes steps to `reviewed` by recording who approved the pull request, when,
// and where. Run after the reviewing PR exists, so the link is real:
//
//   ./gradlew :carp.dsp.steps:reviewSteps \
//       -Previewer=@handle \
//       -Ppr=https://github.com/carp-dk/carp-dsp/pull/142
//
// Optionally limit to specific steps:
//       -Psteps=core.reshape.select-columns,sensing.steps.clean
//
// `reviewedHash` is set to the step's content hash *now*, which is what the
// reviewer approved. Any later edit changes contentHash, the two diverge, and
// the conformance gate reports the step as changed since review.
tasks.register("reviewSteps") {
    group = "step library"
    description = "Record a pull-request review against one or more steps"

    doLast {
        val reviewer = (findProperty("reviewer") as String?)
            ?: error("Missing -Previewer=@handle")
        val pr = (findProperty("pr") as String?)
            ?: error("Missing -Ppr=<pull request URL>")
        require(pr.startsWith("http")) { "-Ppr must be a link to the pull request, got '$pr'" }

        val only = (findProperty("steps") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val today = LocalDate.now().toString()

        var reviewed = 0
        stepDirectories().forEach { dir ->
            val id = stepIdOf(dir)
            if (only != null && id !in only) return@forEach

            val file = dir.resolve("certification.yaml")
            val hash = contentHashOf(dir)
            val version = Regex("""^version:\s*"?([^"\n]+)"?""", RegexOption.MULTILINE)
                .find(file.takeIf { it.isFile }?.readText().orEmpty())?.groupValues?.get(1) ?: "1.0"

            file.writeText(
                buildString {
                    appendLine("""id: "$id"""")
                    appendLine("""version: "$version"""")
                    appendLine("""level: "reviewed"""")
                    appendLine("""contentHash: "$hash"""")
                    appendLine("""reviewedOn: "$today"""")
                    appendLine("""reviewer: "$reviewer"""")
                    appendLine("""reviewedPr: "$pr"""")
                    appendLine("""reviewedHash: "$hash"""")
                }
            )
            reviewed++
            logger.lifecycle("  $id  reviewed by $reviewer")
        }
        logger.lifecycle("Recorded a review on $reviewed step(s) against $pr")
        if (only != null && reviewed != only.size) {
            logger.warn("Requested ${only.size} step(s) but matched $reviewed - check the ids")
        }
    }
}
