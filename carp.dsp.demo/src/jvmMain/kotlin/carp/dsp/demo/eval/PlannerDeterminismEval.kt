package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.io.DemoIo
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.common.application.UUID

/**
 * Planner determinism eval (paper: Evaluation / Reproducibility, plan determinism).
 *
 * Plans the mobgap gait analysis workflow N times (default 100) and verifies that
 * every plan is identical at the claim level: same step UUIDs, same topological
 * order, same environment refs.
 *
 * Two known caveats this eval documents explicitly:
 * 1. `ExecutionPlan.planId` is `UUID.randomUUID()` by design - it is excluded from
 *    the fingerprint and reported separately.
 * 2. `WorkflowDescriptorImporter` defaults to a RANDOM workflow namespace, so step
 *    UUIDs are only deterministic when a fixed namespace is passed (as recommended
 *    by def). This is by importing with the default namespace in part 2: default constructor.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.eval.PlannerDeterminismEvalKt
 * Optional first arg: iteration count (default 100).
 *
 * Results are printed and appended to `<project>/eval_results/planner-determinism.txt`.
 */

/** Fixed namespace for reproducible step-UUID generation (UUID v5). */
private val FIXED_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")

fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toIntOrNull() ?: 100
    val yaml = DemoIo.loadResource("workflows/mobgap-gait-analysis.yaml")
    val codec = WorkflowYamlCodec()

    // Part 1: fixed namespace (the reproducible path)
    val fingerprints = mutableSetOf<String>()
    val planIds = mutableSetOf<String>()
    var firstFingerprint: String? = null
    repeat(iterations) {
        val descriptor = codec.decodeOrThrow(yaml)
        val definition = WorkflowDescriptorImporter(FIXED_NAMESPACE).import(descriptor)
        val plan = DefaultExecutionPlanner().plan(definition)
        plan.validate()
        val fp = fingerprint(plan)
        if (firstFingerprint == null) firstFingerprint = fp
        fingerprints += fp
        planIds += plan.planId
    }

    // Part 2: default constructor (random namespace) - documents the caveat
    val defaultNamespaceFingerprints = (1..5).map {
        val descriptor = codec.decodeOrThrow(yaml)
        val definition = WorkflowDescriptorImporter().import(descriptor)
        fingerprint(DefaultExecutionPlanner().plan(definition))
    }.toSet()

    val report = buildString {
        appendLine("=".repeat(70))
        appendLine("Planner determinism eval - ${java.time.Instant.now()}")
        appendLine("Workflow: mobgap-gait-analysis.yaml")
        appendLine()
        appendLine("Part 1: fixed workflow namespace ($FIXED_NAMESPACE)")
        appendLine("  Invocations:            $iterations")
        appendLine("  Distinct fingerprints:  ${fingerprints.size} (expected: 1)")
        appendLine("  Distinct planIds:       ${planIds.size} (random by design, expected: $iterations)")
        appendLine("  Claim holds:            ${fingerprints.size == 1}")
        appendLine()
        appendLine("Part 2: default importer (random namespace), 5 invocations")
        appendLine("  Distinct fingerprints:  ${defaultNamespaceFingerprints.size}")
        appendLine("  -> step UUIDs are only stable with a fixed namespace;")
        appendLine("     scope the paper claim accordingly or fix the demo default.")
        appendLine()
        appendLine("Fingerprint fields: execution index | step UUID | step name | environmentRef,")
        appendLine("plus sorted requiredEnvironmentRefs and issue count. planId excluded.")
        appendLine()
        appendLine("Reference plan (first invocation):")
        appendLine(firstFingerprint ?: "<none>")
        appendLine("=".repeat(70))
    }

    println(report)
    val outFile = DemoIo.evalResultsDir().resolve("planner-determinism.txt")
    outFile.appendText(report + "\n")
    println("Appended results to: ${outFile.absolutePath}")
}

/**
 * Claim-level fingerprint: step order, step UUIDs, names, environment refs.
 * Excludes planId (random by design).
 */
private fun fingerprint(plan: ExecutionPlan): String {
    val steps = plan.steps.withIndex().joinToString("\n") { (i, s) ->
        "  $i | ${s.metadata.id} | ${s.metadata.name} | env=${s.environmentRef}"
    }
    val envRefs = plan.requiredEnvironmentRefs.map { it.toString() }.sorted()
    return steps + "\n  envRefs=$envRefs\n  issues=${plan.issues.size}"
}

