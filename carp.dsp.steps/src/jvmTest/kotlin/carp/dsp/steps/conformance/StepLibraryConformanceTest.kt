package carp.dsp.steps.conformance

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.LibraryDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.resolve.CertificationLevel
import carp.dsp.core.application.authoring.resolve.StepCertificationFile
import carp.dsp.core.application.authoring.resolve.StepContentHash
import carp.dsp.core.application.authoring.validation.WorkflowLinter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import dk.cachet.carp.common.application.UUID
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The automated half of the contribution review.
 *
 * Every step in the library is discovered and checked, so adding a step
 * adds no test wiring - a malformed contribution fails `build` on its own.
 */
class StepLibraryConformanceTest
{
    private val codec = WorkflowYamlCodec()

    /** Fixed namespace so step ids are deterministic, as elsewhere in the framework. */
    private val namespace = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")

    private val tiers = setOf("core", "sensing", "analysis")

    private data class LibraryStep(val id: String, val dir: File, val yaml: String)

    // ── Publication metadata ──────────────────────────────────────────────────
    //
    // `library:` is a typed field on the descriptor, so it is parsed once by the
    // codec rather than reparsed here through a permissive reader.

    private fun library(step: LibraryStep): LibraryDescriptor =
        decode(step).library ?: fail("${step.id}: no `library:` block")

    /**
     * Every `step.yaml` under the resource tree, with the tier/subject path it sits in.
     *
     * Resolved from the compiled resources directory rather than the classpath so
     * sibling files (implementations, fixtures) can be read relative to the step.
     */
    private fun steps(): List<LibraryStep>
    {
        val root = resourceRoot().resolve("steps")
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.name == "step.yaml" }
            .map { LibraryStep(id = it.parentFile.relativeId(root), dir = it.parentFile, yaml = it.readText()) }
            .sortedBy { it.id }
            .toList()
    }

    private fun File.relativeId(root: File): String =
        relativeTo(root).invariantSeparatorsPath.replace('/', '.')

    private fun resourceRoot(): File
    {
        val marker = javaClass.classLoader.getResource("environments")
            ?: error("Resource root not found - has the module been built?")
        return File(marker.toURI()).parentFile
    }

    private fun decode(step: LibraryStep): WorkflowDescriptor =
        runCatching { codec.decodeOrThrow(step.yaml) }
            .getOrElse { fail("${step.id}: step.yaml does not decode - ${it.message}") }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    fun `library contains at least one step`()
    {
        assertTrue(steps().isNotEmpty(), "No steps found - the conformance gate would pass vacuously")
    }

    @Test
    fun `every step declares exactly one step`()
    {
        steps().forEach { step ->
            val descriptor = decode(step)
            assertEquals(
                1,
                descriptor.steps.size,
                "${step.id}: a library step is a single-step workflow, found ${descriptor.steps.size}"
            )
        }
    }

    @Test
    fun `every step sits in a known tier and matches its directory`()
    {
        steps().forEach { step ->
            val tier = step.id.substringBefore('.')
            assertTrue(tier in tiers, "${step.id}: unknown tier '$tier', expected one of $tiers")

            val declaredId = decode(step).metadata.id
            assertEquals(
                declaredId,
                step.id,
                "${step.id}: metadata.id is '$declaredId' but the directory implies '${step.id}'"
            )
        }
    }

    // ── Tier membership ───────────────────────────────────────────────────────
    //
    // The tier boundary has an objective test rather than resting on taste, so it
    // is enforced rather than reviewed: does any port declare a CARP data type?

    /** The distinct CARP data types declared across this step's port fields. */
    private fun portDataTypes(step: LibraryStep): Set<String> =
        (decode(step).steps.single() as DefinedStepDescriptor).let { it.inputs + it.outputs }
            .flatMap { it.descriptor?.fields.orEmpty() }
            .mapNotNull { it.dataType?.toString() }
            .toSet()

    @Test
    fun `the library block agrees with the directory`()
    {
        steps().forEach { step ->
            val block = library(step)
            val parts = step.id.split('.')
            assertEquals(
                block.tier,
                parts[0],
                "${step.id}: library.tier is '${block.tier}' but the path says '${parts[0]}'"
            )
            assertEquals(
                block.subject,
                parts[1],
                "${step.id}: library.subject is '${block.subject}' but the path says '${parts[1]}'"
            )
        }
    }

    @Test
    fun `core steps reference no CARP data type`()
    {
        steps().filter { it.id.startsWith("core.") }.forEach { step ->
            val referenced = portDataTypes(step)
            assertTrue(
                referenced.isEmpty(),
                "${step.id}: a core step must be domain-agnostic but its ports declare $referenced. " +
                    "Move it to sensing.* or analysis.*"
            )
        }
    }

    @Test
    fun `sensing steps reference exactly one CARP data type`()
    {
        steps().filter { it.id.startsWith("sensing.") }.forEach { step ->
            val referenced = portDataTypes(step)
            assertEquals(
                1, referenced.size,
                    "${step.id}: a sensing step is tied to exactly one collected data type, " +
                        "found ${referenced.size} ($referenced). Several data types means " +
                        "analysis.*, none means core.*"
            )
        }
    }

    @Test
    fun `analysis steps reference more than one CARP data type`()
    {
        steps().filter { it.id.startsWith("analysis.") }.forEach { step ->
            val referenced = portDataTypes(step)
            assertTrue(
                referenced.size > 1,
                "${step.id}: an analysis step combines several collected data types, " +
                    "found ${referenced.size} ($referenced). One data type means sensing.*, " +
                    "none means core.*"
            )
        }
    }

    @Test
    fun `declared CARP data types are namespaced`()
    {
        steps().forEach { step ->
            portDataTypes(step).forEach { dataType ->
                assertTrue(
                    dataType.contains('.') && !dataType.endsWith('.'),
                    "${step.id}: '$dataType' is not a namespaced CARP data type"
                )
            }
        }
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    @Test
    fun `every declared port has a file format`()
    {
        steps().forEach { step ->
            (decode(step).steps.single() as DefinedStepDescriptor).let { s ->
                (s.inputs + s.outputs).forEach { port ->
                    assertTrue(
                        !port.descriptor?.fileFormat.isNullOrBlank(),
                        "${step.id}: port '${port.id}' declares no fileFormat"
                    )
                }
            }
        }
    }

    @Test
    fun `every declared port carries a format term`()
    {
        steps().forEach { step ->
            (decode(step).steps.single() as DefinedStepDescriptor).let { s ->
                (s.inputs + s.outputs).forEach { port ->
                    assertTrue(
                        port.descriptor?.formatRef != null,
                        "${step.id}: port '${port.id}' has no formatRef - required for certified steps"
                    )
                }
            }
        }
    }

    @Test
    fun `every typed field carries an ontology term`()
    {
        steps().forEach { step ->
            val s = decode(step).steps.single() as DefinedStepDescriptor
            val ports = s.inputs + s.outputs
            ports.forEach { port ->
                val fields = port.descriptor?.fields.orEmpty()
                    .filter { it.dataType != null }
                fields.forEach { field ->
                    assertTrue(
                        field.ontologyRef != null,
                        "${step.id}: port '${port.id}' field '${field.name}' declares a " +
                            "data type but no ontologyRef - required for certified steps"
                    )
                }
            }
        }
    }

    @Test
    fun `every step has a description and at least one tag`()
    {
        steps().forEach { step ->
            val metadata = decode(step).metadata
            assertTrue(
                !metadata.description.isNullOrBlank(),
                "${step.id}: discovery metadata incomplete - no description"
            )
            assertTrue(
                metadata.tags.isNotEmpty(),
                "${step.id}: discovery metadata incomplete - no tags"
            )
        }
    }

    // ── It must plan ─────────────────────────────────────────────────

    @Test
    fun `every step lints without errors`()
    {
        steps().forEach { step ->
            val issues = WorkflowLinter.lint(decode(step)).issues
                .filter { it.severity == ValidationSeverity.ERROR }
            assertTrue(
                issues.isEmpty(),
                "${step.id}: lint errors - ${issues.joinToString { "${it.code}: ${it.message}" }}"
            )
        }
    }

    @Test
    fun `every step plans with zero errors`()
    {
        steps().forEach { step ->
            val definition = WorkflowDescriptorImporter(namespace).import(decode(step))
            val plan = DefaultExecutionPlanner().plan(definition)
            val errors = plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
            assertTrue(
                errors.isEmpty(),
                "${step.id}: plan errors - ${errors.joinToString { "${it.code}: ${it.message}" }}"
            )
            assertTrue(plan.isRunnable(), "${step.id}: plan is not runnable")
        }
    }

    // ── Files a step must ship ────────────────────────────────────────────────

    @Test
    fun `every step ships a README and a reference fixture`()
    {
        steps().forEach { step ->
            assertTrue(step.dir.resolve("README.md").isFile, "${step.id}: no README.md")

            val reference = step.dir.resolve("reference")
            assertTrue(reference.isDirectory, "${step.id}: no reference/ fixture directory")
            assertTrue(
                reference.listFiles().orEmpty().isNotEmpty(),
                "${step.id}: reference/ is empty - every implementation must have a fixture to reproduce"
            )
        }
    }

    /**
     * The sections a step README must have, in the order a reader decides in:
     * is this the right thing, can I feed it my data, what comes out, how does it
     * work, what might make it wrong for me.
     *
     * Checked because the sections that get dropped are the ones a contributor has
     * least to say about - most often `Choices and limits` and `References`, which
     * are exactly the two a researcher needs to judge whether a step's defaults
     * suit their study. A missing heading is then indistinguishable from a step
     * with nothing to disclose. The template at `templates/python/README.md` says
     * to state an absence rather than omit the section.
     *
     * Presence and order only - no check on what is written under them.
     */
    private val readmeSections = listOf(
        "Overview",
        "Data it needs",
        "What you get",
        "How it works",
        "Choices and limits",
        "Options",
        "References",
        "Implementations",
    )

    @Test
    fun `every README carries the standard sections in order`()
    {
        steps().forEach { step ->
            val headings = step.dir.resolve("README.md").readLines()
                .filter { it.startsWith("## ") }
                .map { it.removePrefix("## ").trim() }

            val missing = readmeSections - headings.toSet()
            assertTrue(
                missing.isEmpty(),
                "${step.id}: README is missing section(s) ${missing.joinToString { "'$it'" }}. " +
                    "See templates/python/README.md - a section with nothing to report should say " +
                    "so rather than be dropped."
            )

            assertEquals(
                readmeSections,
                headings.filter { it in readmeSections },
                "${step.id}: README sections are out of order. Expected " +
                    readmeSections.joinToString(" -> ")
            )
        }
    }

    @Test
    fun `every declared implementation exists`()
    {
        steps().forEach { step ->
            val implementations = library(step).implementations
            assertTrue(
                implementations.isNotEmpty(),
                "${step.id}: declares no implementations - a published step ships at least one"
            )
            implementations.forEach { implementation ->
                assertTrue(
                    step.dir.resolve(implementation.path).isFile,
                    "${step.id}: declares a ${implementation.language} implementation at " +
                        "'${implementation.path}', which does not exist"
                )
            }
        }
    }

    /**
     * Implementation files are staged into one shared workspace root at their
     * path relative to the step directory, unprefixed. Two steps in the same
     * workflow that ship a file at the same relative path therefore collide:
     * `WorkspaceProvisioner` refuses to let one step's bytes shadow another's and
     * fails the run.
     *
     * That failure arrives during execution, from a workflow that linted, planned
     * and certified cleanly - so it is checked here instead, across the whole
     * library. Two steps that could never appear together are still rejected,
     * which is stricter than the provisioner needs. The alternative is to know
     * every composition in advance, which the library cannot.
     */
    @Test
    fun `no two steps ship a file at the same path`()
    {
        val owners = HashMap<String, MutableList<String>>()
        steps().forEach { step ->
            val implDir = step.dir.resolve("impl")
            if (!implDir.isDirectory) return@forEach
            implDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(step.dir).invariantSeparatorsPath
                owners.getOrPut(relative) { mutableListOf() }.add(step.id)
            }
        }

        val collisions = owners.filterValues { it.size > 1 }
        assertTrue(
            collisions.isEmpty(),
            "these paths are shipped by more than one step, so a workflow using both " +
                "would fail when the workspace is staged: " +
                collisions.entries.joinToString("; ") { (path, ids) ->
                    "'$path' by ${ids.sorted().joinToString(", ")}"
                } +
                ". Give each step's implementation a distinct filename."
        )
    }

    @Test
    fun `every step ships tests for its implementations`()
    {
        steps().forEach { step ->
            library(step).implementations.forEach { implementation ->
                val implementationDir = step.dir.resolve(implementation.path).parentFile
                val tests = implementationDir?.listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith("test_") }
                assertTrue(
                    tests.isNotEmpty(),
                    "${step.id}: the ${implementation.language} implementation ships no tests. " +
                        "A reference fixture shows the step was validated once; tests are what keep " +
                        "it validated. Add test_*.py beside the implementation."
                )
            }
        }
    }

    @Test
    fun `the declared reference fixture exists`()
    {
        steps().forEach { step ->
            val reference = library(step).reference
                ?: fail("${step.id}: no reference fixture declared")

            // A generator takes no input, so it has none to fix. The expected output is
            // still required. Note this is NOT an allowance for network loaders: a step
            // that downloads still ships a local archive as its input fixture and is
            // exercised over a file:// URL - see core.io.fetch-zenodo as an example.
            reference.input?.let {
                assertTrue( step.dir.resolve(it).isFile, "${step.id}: reference input '$it' does not exist" )
            }
            val expected = reference.expected
                ?: fail("${step.id}: reference fixture declares no expected output")
            assertTrue(
                step.dir.resolve(expected).isFile,
                "${step.id}: reference expected output '$expected' does not exist"
            )
        }
    }

    @Test
    fun `the default environment is one the step declares`()
    {
        steps().forEach { step ->
            val default = library(step).environment?.default
                ?: fail("${step.id}: declares no default environment")
            assertTrue(
                default in decode(step).environments.keys,
                "${step.id}: library.environment.default is '$default', which the step does not declare"
            )
        }
    }

    @Test
    fun `every step references an implementation that exists`()
    {
        steps().forEach { step ->
            val task = (decode(step).steps.single() as DefinedStepDescriptor).task
            val scriptPath = Regex("""scriptPath:\s*"([^"]+)"""")
                .find(step.yaml)?.groupValues?.get(1)

            if (scriptPath != null)
            {
                assertTrue(
                    step.dir.resolve(scriptPath).isFile,
                    "${step.id}: task ${task.name} points at '$scriptPath', which does not exist"
                )
            }
        }
    }

    // ── Certification ─────────────────────────────────────────────────────────
    //
    // The published content hash is what a consumer's steps.lock pins against, so
    // it has to describe the step as it actually ships. Enforcing it here means a
    // step cannot change without its certification record being refreshed.

    @Test
    fun `every step publishes a certification record that matches its content`()
    {
        steps().forEach { step ->
            val record = StepCertificationFile.read( step.dir )
                ?: fail("${step.id}: no certification.yaml")

            assertEquals( step.id, record.id, "${step.id}: certification record is for '${record.id}'" )
            assertEquals(
                decode(step).metadata.version,
                record.version,
                "${step.id}: certification records version '${record.version}' but the step declares " +
                    "'${decode(step).metadata.version}'"
            )

            val published = record.contentHash
                ?: fail("${step.id}: certification record has no contentHash - run :carp.dsp.steps:certifySteps")
            assertEquals(
                StepContentHash.of( step.dir ),
                published,
                "${step.id}: content has changed since it was certified - run :carp.dsp.steps:certifySteps"
            )
        }
    }

    @Test
    fun `every certification level is one the library defines`()
    {
        steps().forEach { step ->
            val record = StepCertificationFile.read( step.dir ) ?: fail("${step.id}: no certification.yaml")
            assertTrue(
                record.level in CertificationLevel.ALL,
                "${step.id}: level '${record.level}' is not one of ${CertificationLevel.ALL}"
            )
        }
    }

    /**
     * A `reviewed` step must name who reviewed it, when, and where.
     *
     * The gate cannot check that a review was thorough - that is the reviewer's
     * job. What it can check is that the claim is *attributable*: a step calling
     * itself reviewed with no reviewer is asserting something nobody stands
     * behind, which is worse than claiming nothing.
     */
    @Test
    fun `a reviewed step records who reviewed it, when, and in which pull request`()
    {
        steps().filter { StepCertificationFile.read( it.dir )?.level == CertificationLevel.REVIEWED }
            .forEach { step ->
                val record = StepCertificationFile.read( step.dir )!!
                listOf(
                    "reviewer" to record.reviewer,
                    "reviewedOn" to record.reviewedOn,
                    "reviewedPr" to record.reviewedPr,
                    "reviewedHash" to record.reviewedHash,
                ).forEach { ( field, value ) ->
                    assertTrue(
                        !value.isNullOrBlank(),
                        "${step.id}: level is '${CertificationLevel.REVIEWED}' but $field is not set. " +
                            "Either record the review, or set level to '${CertificationLevel.GATED}'."
                    )
                }
                assertTrue(
                    record.reviewedPr!!.startsWith( "http" ),
                    "${step.id}: reviewedPr should be a link to the pull request, found '${record.reviewedPr}'"
                )
            }
    }

    /**
     * A step edited after review is no longer reviewed.
     *
     * `certifySteps` refreshes `contentHash` whenever a step changes. Without
     * this check the reviewer's name would travel with the step through edits
     * they never saw, which is exactly the overclaim the two-level scheme exists
     * to prevent.
     */
    @Test
    fun `a reviewed step has not changed since it was reviewed`()
    {
        steps().forEach { step ->
            val record = StepCertificationFile.read( step.dir ) ?: return@forEach
            if ( record.level != CertificationLevel.REVIEWED ) return@forEach
            // Asserted through CertificationRecord.isReviewCurrent rather than by
            // comparing the hashes here, so "is this review still valid" has one
            // definition. Restating the comparison in the test would let the two
            // drift, and the test is the only thing that runs.
            assertTrue(
                record.isReviewCurrent,
                "${step.id}: content has changed since ${record.reviewer} reviewed it in " +
                    "${record.reviewedPr}. Reviewed ${record.reviewedHash}, ships " +
                    "${record.contentHash}. Re-review and update reviewedHash, or drop the " +
                    "level back to '${CertificationLevel.GATED}'."
            )
        }
    }

    /**
     * A step with no method citation must say so in its README.
     *
     * The two halves of the provenance claim are written in different places and
     * can disagree: `method.citation` is machine-readable, the README is what a
     * researcher reads. A null citation with no explanation is ambiguous between
     * "this needs no source" and "nobody found one", and those are very different
     * things for a step encoding a physiological threshold.
     *
     * Deliberately a weak check: it does not judge the explanation, only that one
     * was written. The gate cannot tell a good justification from a bad one.
     */
    @Test
    fun `a step without a method citation states why in its README`()
    {
        steps().forEach { step ->
            val citation = library(step).method?.citation
            if ( !citation.isNullOrBlank() ) return@forEach

            val references = step.dir.resolve("README.md").readText()
                .substringAfter("## References", "")
                .substringBefore("\n## ")
            assertTrue(
                references.contains("No method paper", ignoreCase = true),
                "${step.id}: library.method.citation is null, so the README's References " +
                    "section must say so - start it with \"No method paper: ...\" and explain " +
                    "what the step implements instead."
            )
        }
    }

    // ── Environment catalogue ─────────────────────────────────────────────────

    @Test
    fun `inlined environments match the catalogue`()
    {
        val catalogue = resourceRoot().resolve("environments")
            .listFiles { f: File -> f.extension == "yaml" }.orEmpty()
            .associate { file ->
                val id = Regex("""^id:\s*"([^"]+)"""", RegexOption.MULTILINE)
                    .find(file.readText())?.groupValues?.get(1)
                    ?: error("${file.name}: no id")
                id to file.readText()
            }

        steps().forEach { step ->
            decode(step).environments.forEach { (envId, env) ->
                val canonical = catalogue[envId]
                    ?: fail(
                        "${step.id}: environment '$envId' is not in the catalogue. " +
                            "Reuse a catalogue environment, or submit a new one with the step."
                    )

                // Compare the fields that affect execution, not formatting.
                assertTrue(
                    canonical.contains("kind: \"${env.kind}\""),
                    "${step.id}: inlined environment '$envId' has kind '${env.kind}', " +
                        "which differs from the catalogue definition"
                )
                env.spec["dependencies"]?.forEach { dependency ->
                    assertTrue(
                        canonical.contains(dependency),
                        "${step.id}: inlined environment '$envId' declares '$dependency', " +
                            "which the catalogue definition does not"
                    )
                }
            }
        }
    }
}
