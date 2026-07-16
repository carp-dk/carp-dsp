package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import java.io.File
import java.time.Instant

/**
 * Step-reuse eval (paper: Evaluation / Step reuse, Table `tab:step-reuse`).
 *
 * Three HR/step workflows are composed of a shared library of six typed steps
 * (scripts/hr_lib, over the open Fitbit dataset Zenodo 53894). This measures, from the
 * real artefacts, how much the library is reused:
 *   - which library step each workflow references (incidence -> the reuse table)
 *   - total references, distinct steps, per-step reuse counts
 *   - duplicated step bodies under CARP: 0 (workflows reference shared scripts)
 *   - what a monolithic re-implementation would incur: it inlines one copy of every
 *     referenced step, so (references - distinct) copies are redundant duplicates
 *   - library size for scale (SLOC of the six step scripts, written once)
 *
 * All static (no pipeline run): step reuse is an authoring property. Each workflow is
 * planned only to confirm the composition is accepted with zero errors.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:evalStepReuse
 *
 * Writes eval_results/step-reuse.txt, step-reuse.csv, and step-reuse-table.tex.
 */

private val SR_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")
private const val SR_STEP_ANCHOR = "\n  - id: \""

private val WORKFLOWS = listOf(
    "activity-summary" to "workflows/wf-activity-summary.yaml",
    "anomaly-report" to "workflows/wf-anomaly-report.yaml",
    "minimal-summary" to "workflows/wf-minimal-summary.yaml"
)

/** Pipeline order for stable table rows. */
private val STEP_ORDER = listOf(
    "load-hr-steps", "clean-resample", "daily-features", "summarise", "detect-anomaly", "visualise"
)

fun main() {
    val codec = WorkflowYamlCodec()

    // Plan each workflow (confirm reuse-safe) + collect step incidence from the YAML.
    val incidence = LinkedHashMap<String, MutableList<String>>()  // stepId -> workflow labels
    val scriptOf = LinkedHashMap<String, String>()                // stepId -> script file
    val accepted = LinkedHashMap<String, Boolean>()
    for ((label, resource) in WORKFLOWS) {
        val yaml = loadResource(resource)
        val plan = DefaultExecutionPlanner().plan(
            WorkflowDescriptorImporter(SR_NAMESPACE).import(codec.decodeOrThrow(yaml))
        )
        accepted[label] = plan.issues.none { it.severity == PlanIssueSeverity.ERROR }
        stepBlocks(yaml).forEach { (id, block) ->
            incidence.getOrPut(id) { mutableListOf() }.add(label)
            scriptOf[id] = Regex("scriptPath:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)?.substringAfterLast('/')
                ?: scriptOf[id] ?: "?"
        }
    }

    val distinct = incidence.keys.size
    val references = incidence.values.sumOf { it.size }
    val redundantCopies = references - distinct

    // Library size (SLOC of each step script, written once): non-blank, non-comment lines.
    val slocOf = STEP_ORDER.associateWith { id ->
        scriptOf[id]?.let { sloc(loadResource("scripts/hr_lib/$it")) } ?: 0
    }
    val librarySloc = slocOf.values.sum()
    val projectedDupSloc = STEP_ORDER.sumOf { id -> slocOf[id]!! * ((incidence[id]?.size ?: 0) - 1).coerceAtLeast(0) }

    val report = buildString {
        appendLine("=".repeat(72))
        appendLine("Step-reuse eval - ${Instant.now()}")
        appendLine("Library: scripts/hr_lib (open Fitbit data, Zenodo 53894)")
        appendLine()
        appendLine("Workflows planned (0 ERROR issues each): ${accepted.all { it.value }}  $accepted")
        appendLine()
        appendLine("  %-16s  %-7s  %s".format("library step", "reuse", "workflows"))
        STEP_ORDER.forEach { id ->
            appendLine("  %-16s  x%-6d %s".format(id, incidence[id]?.size ?: 0, incidence[id] ?: emptyList<String>()))
        }
        appendLine()
        appendLine("Distinct library steps:        $distinct")
        appendLine("References across workflows:   $references")
        appendLine("Duplicated step bodies (CARP): 0  (workflows reference shared scripts)")
        appendLine("Monolithic build would inline: $references copies -> $redundantCopies redundant " +
            "(${100 * redundantCopies / references}% of inlined)")
        appendLine("Library size (written once):   $librarySloc SLOC across $distinct steps")
        appendLine("Projected duplicated SLOC:     $projectedDupSloc (monolithic)")
        appendLine("=".repeat(72))
    }
    println(report)

    val dir = evalResultsDir()
    dir.resolve("step-reuse.txt").writeText(report + "\n")
    dir.resolve("step-reuse.csv").writeText(
        buildString {
            appendLine("library_step,reuse_count,workflows,sloc")
            STEP_ORDER.forEach { id ->
                appendLine("$id,${incidence[id]?.size ?: 0},${incidence[id]?.joinToString("|")},${slocOf[id]}")
            }
            appendLine("TOTAL,$references,,${librarySloc}")
            appendLine("# distinct=$distinct references=$references redundant_copies=$redundantCopies dup_sloc=$projectedDupSloc")
        }
    )
    dir.resolve("step-reuse-table.tex").writeText(latexTable(incidence))
    println("Wrote step-reuse.txt, step-reuse.csv, step-reuse-table.tex to: ${dir.absolutePath}")
}

/** Regenerate the reuse-table body rows so the paper table stays backed by the eval. */
private fun latexTable(incidence: Map<String, List<String>>): String {
    val colOf = mapOf("activity-summary" to "blue", "anomaly-report" to "red", "minimal-summary" to "green")
    val cols = listOf("activity-summary", "anomaly-report", "minimal-summary")
    return buildString {
        appendLine("% GENERATED by StepReuseEval - reuse table body rows")
        STEP_ORDER.forEach { id ->
            val used = incidence[id] ?: emptyList()
            val cells = cols.joinToString(" & ") { c -> if (c in used) "\\U{${colOf[c]}}" else "" }
            appendLine("\\texttt{$id} & $cells & $\\times ${used.size}$ \\\\")
        }
        val used = cols.map { c -> incidence.values.count { c in it } }
        appendLine("\\midrule")
        appendLine("Steps used & ${used.joinToString(" & ")} & ${incidence.values.sumOf { it.size }} \\\\")
    }
}

/** Non-blank, non-comment SLOC, excluding a leading triple-quoted module docstring. */
private fun sloc(src: String): Int {
    val lines = src.lines()
    var i = 0
    // skip leading blanks/comments to find a possible docstring
    while (i < lines.size && (lines[i].isBlank() || lines[i].trimStart().startsWith("#"))) i++
    var body = lines
    if (i < lines.size && (lines[i].trimStart().startsWith("\"\"\"") || lines[i].trimStart().startsWith("'''"))) {
        val q = lines[i].trimStart().take(3)
        var j: Int
        // handle single-line docstring
        if (lines[i].trimStart().drop(3).contains(q)) {
            body = lines.filterIndexed { idx, _ -> idx != i }
        } else {
            j = i + 1
            while (j < lines.size && !lines[j].contains(q)) j++
            body = lines.filterIndexed { idx, _ -> idx !in i..j }
        }
    }
    return body.count { it.isNotBlank() && !it.trimStart().startsWith("#") }
}

private fun stepBlocks(yaml: String): Map<String, String> {
    val blocks = LinkedHashMap<String, String>()
    var i = yaml.indexOf(SR_STEP_ANCHOR)
    while (i >= 0) {
        val start = i + 1
        val idStart = start + "  - id: \"".length
        val idEnd = yaml.indexOf('"', idStart)
        val id = yaml.substring(idStart, idEnd)
        val next = yaml.indexOf(SR_STEP_ANCHOR, start)
        val end = if (next < 0) yaml.length else next + 1
        blocks[id] = yaml.substring(start, end)
        i = next
    }
    return blocks
}

private object StepReuseAnchor

private fun loadResource(path: String): String =
    StepReuseAnchor::class.java.classLoader.getResource(path)?.readText()
        ?: throw IllegalStateException("Resource not found: $path")

private fun evalResultsDir(): File {
    val classPath = StepReuseAnchor::class.java.protectionDomain.codeSource.location.toURI().path
    val projectRoot = File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
        ?: throw IllegalStateException("Cannot determine project root")
    return projectRoot.resolve("eval_results").apply { mkdirs() }
}
