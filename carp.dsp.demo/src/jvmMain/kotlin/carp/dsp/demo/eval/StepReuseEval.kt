package carp.dsp.demo.eval

import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowSource
import carp.dsp.steps.ClasspathStepLibrary
import carp.dsp.demo.io.DemoIo
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import java.io.File
import java.time.Instant

/**
 * Step-reuse eval (paper: Evaluation / Step reuse, Table `tab:step-reuse`).
 *
 * Measures the same three HR/step pipelines authored two ways, so the reuse claim
 * is a comparison between real artefacts rather than a projection:
 *
 *   - **inline** (v1): every workflow re-declares each step in full - its task,
 *     environment and typed ports - even where the step is identical to one
 *     another workflow already declares. The scripts are shared; the *declaration*
 *     is written out again per workflow.
 *   - **`uses:`** (v2): every workflow supplies only wiring, and the declaration
 *     comes from the certified library, pinned by content hash.
 *
 * Two numbers separate them, and they are different claims:
 *
 *   - **references** - how many times a step is used. A step used once is used,
 *     not reused; this column says so rather than reporting "reuse x1".
 *   - **repeats avoided** - `references - distinct`, the number of times a step
 *     did not have to be written again. This is the reuse claim, and it is also
 *     exactly what a monolithic build would duplicate.
 *
 * Neither number captures the other half of the argument: under `uses:` none of
 * the steps had to be *written* at all, including the one referenced once. That
 * is reported separately as the authored-elsewhere count.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:evalStepReuse
 *
 * Writes eval_results/step-reuse.txt, step-reuse.csv, step-reuse-table.tex,
 * step-reuse-numbers.tex and notation-cost-numbers.tex (lines per step over
 * [NOTATION_WORKFLOWS], for Table `tab:notation-cost`). With -PpaperDir the table and
 * the numbers are also written to the paper's generated/ folder.
 */

private const val SR_STEP_ANCHOR = "\n  - id: \""

/** The same three pipelines, authored inline. */
private val INLINE_WORKFLOWS = listOf(
    "activity-summary" to "workflows/wf-activity-summary.yaml",
    "anomaly-report" to "workflows/wf-anomaly-report.yaml",
    "minimal-summary" to "workflows/wf-minimal-summary.yaml",
)

/** The same three pipelines, composed of the certified library. */
private val LIBRARY_WORKFLOWS = listOf(
    "activity-summary" to "workflows/wf-activity-summary-v2.yaml",
    "anomaly-report" to "workflows/wf-anomaly-report-v2.yaml",
    "minimal-summary" to "workflows/wf-minimal-summary-v2.yaml",
)

/** The workflows notation cost is measured over: Use Case 1 and the three analyses, both ways. */
private val NOTATION_WORKFLOWS =
    listOf("workflows/mobgap-gait-analysis.yaml") + INLINE_WORKFLOWS.map { it.second } + LIBRARY_WORKFLOWS.map { it.second }

private val USES_LINE = Regex("^\\s*uses:", RegexOption.MULTILINE)

private val COLUMN_COLOUR = mapOf(
    "activity-summary" to "blue", "anomaly-report" to "red", "minimal-summary" to "green",
)

/**
 * One authoring strategy measured over the three workflows.
 *
 * @property references step -> the workflow labels referencing it, one entry per
 *   reference, so a step used twice in one workflow appears twice.
 * @property declarationLines step -> YAML lines its declaration occupies, counted
 *   once per reference. Under `uses:` a reference is a few lines of wiring; inline
 *   it is the whole step.
 */
private data class Strategy(
    val name: String,
    val references: Map<String, List<String>>,
    val declarationLines: Map<String, Int>,
    val stepOrder: List<String>,
    /**
     * Total steps declared in each workflow, whether referenced or written out.
     *
     * Carried so the table can state how many steps are *not* references. Reading
     * the reference count alone, there is no way to tell whether a workflow is
     * built entirely from the library or only partly - and "entirely" is the
     * stronger claim, so it should be measured rather than asserted in prose.
     */
    val stepsPerWorkflow: Map<String, Int>,
    /**
     * Whether a second use restates the step's declaration.
     *
     * Inline, it does: the task, environment and typed ports are written out again.
     * Under `uses:`, it does not - the second reference adds only wiring, which is
     * genuinely different each time because it names different inputs. Counting
     * that as duplication would be wrong, so duplicated lines are zero by
     * construction rather than by measurement.
     */
    val restatesDeclaration: Boolean,
) {
    val distinct: Int get() = references.keys.size
    val total: Int get() = references.values.sumOf { it.size }
    val repeatsAvoided: Int get() = total - distinct

    /** YAML written for steps across all three workflows. */
    val authoredLines: Int get() = references.entries.sumOf { (id, uses) ->
        (declarationLines[id] ?: 0) * uses.size
    }

    /** Of that, the lines that restate a declaration another workflow already made. */
    val duplicatedLines: Int get() =
        if (!restatesDeclaration) 0
        else references.entries.sumOf { (id, uses) ->
            (declarationLines[id] ?: 0) * (uses.size - 1).coerceAtLeast(0)
        }

    fun countIn(workflow: String): Int = references.values.sumOf { uses -> uses.count { it == workflow } }

    /** Steps the workflow declares, whether referenced or written out. */
    fun stepsIn(workflow: String): Int = stepsPerWorkflow[workflow] ?: 0

    val totalSteps: Int get() = stepsPerWorkflow.values.sum()

    /**
     * Steps written out in the workflow rather than referenced from the library.
     *
     * Under `uses:` this is the steps a reference did not account for. Inline,
     * every step is written out by definition - there is no library to reference -
     * so counting non-references there would report zero, which is the opposite of
     * the truth. [restatesDeclaration] is what distinguishes the two.
     */
    fun inlineIn(workflow: String): Int =
        if (restatesDeclaration) stepsIn(workflow) else stepsIn(workflow) - countIn(workflow)

    val totalInline: Int get() = stepsPerWorkflow.keys.sumOf { inlineIn(it) }
}

fun main() {

    val inline = measure("inline", INLINE_WORKFLOWS, restatesDeclaration = true) { block ->
        // Inline: the step's identity is the script it runs. Two workflows that
        // declare the same script have written the same step twice.
        Regex("scriptPath:\\s*\"([^\"]+)\"").find(block)
            ?.groupValues?.get(1)?.substringAfterLast('/')?.substringBeforeLast('.')
    }
    val library = measure("uses:", LIBRARY_WORKFLOWS, restatesDeclaration = false) { block ->
        Regex("uses:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)?.let(::shortLabel)
    }

    // Every library composition must still plan cleanly once resolved - the table
    // counts references, and this is what makes them references to something real.
    val accepted = LinkedHashMap<String, Boolean>()
    val scratch = DemoIo.evalResultsDir().resolve("step-reuse-plan").apply { mkdirs() }
    for ((label, resource) in LIBRARY_WORKFLOWS) {
        val file = File(scratch, resource.substringAfterLast('/'))
        DemoIo.copyResource(resource, file.toPath())
        val prepared = WorkflowExecutor.filesystem(ClasspathStepLibrary(), scratch.toPath())
            .prepare(WorkflowSource.of(file.toPath()))
        accepted[label] = prepared.plan.issues.none { it.severity == PlanIssueSeverity.ERROR }
    }
    scratch.deleteRecursively()

    val report = buildString {
        appendLine("=".repeat(76))
        appendLine("Step-reuse eval - ${Instant.now()}")
        appendLine("Three HR/step pipelines (open Fitbit data, Zenodo 53894), authored two ways.")
        appendLine()
        appendLine("Library compositions plan with 0 ERROR issues: ${accepted.all { it.value }}  $accepted")
        appendLine()
        appendLine(table(library))
        appendLine()
        appendLine("  %-34s %10s %10s".format("", inline.name, library.name))
        appendLine("  " + "-".repeat(56))
        appendLine("  %-34s %10d %10d".format("distinct steps", inline.distinct, library.distinct))
        appendLine("  %-34s %10d %10d".format("references", inline.total, library.total))
        appendLine("  %-34s %10d %10d".format("repeats avoided", inline.repeatsAvoided, library.repeatsAvoided))
        appendLine("  %-34s %10d %10d".format("step YAML authored (lines)", inline.authoredLines, library.authoredLines))
        appendLine("  %-34s %10d %10d".format("of which duplicated", inline.duplicatedLines, library.duplicatedLines))
        appendLine("  %-34s %10d %10d".format("steps declared inline", inline.totalInline, library.totalInline))
        appendLine("  %-34s %10s %10d".format("steps authored elsewhere", "0", library.distinct))
        appendLine()
        appendLine("  Both share their scripts. What differs is the declaration: inline restates each")
        appendLine("  step's task, environment and typed ports per workflow, while `uses:` states them")
        appendLine("  once in the library and references them, so a second reference adds only wiring.")
        appendLine()
        appendLine("  The two decompositions are not identical, and that is itself a result: composing")
        appendLine("  from generic library steps produced a finer pipeline (${library.distinct} steps against ${inline.distinct}), because a")
        appendLine("  generic step does less each. Read the totals as 'what you write to express this")
        appendLine("  analysis', not as the same pipeline measured twice.")
        appendLine()
        appendLine("  Under `uses:` none of the ${library.distinct} steps was written for these workflows - including the")
        appendLine("  one referenced once, which is reuse of a step someone else authored rather than")
        appendLine("  repeat use of your own.")
        appendLine("=".repeat(76))
    }
    println(report)

    val dir = DemoIo.evalResultsDir()
    dir.resolve("step-reuse.txt").writeText(report + "\n")
    dir.resolve("step-reuse.csv").writeText(csv(inline, library))
    val table = dir.resolve("step-reuse-table.tex").apply { writeText(latexTable(library)) }
    val numbers = dir.resolve("step-reuse-numbers.tex").apply { writeText(latexNumbers(inline, library)) }
    val notation = dir.resolve("notation-cost-numbers.tex").apply { writeText(notationNumbers()) }
    println(
        "Wrote step-reuse.txt, step-reuse.csv, step-reuse-table.tex, step-reuse-numbers.tex, " +
            "notation-cost-numbers.tex to: ${dir.absolutePath}"
    )

    DemoIo.publishToPaper(table, "generated/step-reuse-table.tex")
    DemoIo.publishToPaper(numbers, "generated/step-reuse-numbers.tex")
    DemoIo.publishToPaper(notation, "generated/notation-cost-numbers.tex")
}

/**
 * Lines per step over [NOTATION_WORKFLOWS], as LaTeX macros: count, minimum, median
 * and maximum for steps declared in full and for `uses:` references. A line counts
 * when it is neither blank nor a comment.
 */
private fun notationNumbers(): String = buildString {
    val blocks = NOTATION_WORKFLOWS.flatMap { stepBlocks(DemoIo.loadResource(it)).values }
    val (references, full) = blocks.partition { USES_LINE.containsMatchIn(it) }
    fun lines(block: String) = block.lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }

    appendLine("% GENERATED by StepReuseEval - do not edit; run :carp.dsp.demo:evalStepReuse")
    fun macro(name: String, value: Int) = appendLine("\\newcommand{\\$name}{$value}")
    macro("NotationWorkflows", NOTATION_WORKFLOWS.size)
    listOf("Full" to full, "Ref" to references).forEach { (label, group) ->
        val counts = group.map(::lines).sorted()
        check(counts.isNotEmpty()) { "No ${label.lowercase()} steps in the notation workflows." }
        val mid = counts.size / 2
        val median = if (counts.size % 2 == 1) counts[mid] else Math.round((counts[mid - 1] + counts[mid]) / 2.0).toInt()
        macro("Notation${label}Steps", counts.size)
        macro("Notation${label}Min", counts.first())
        macro("Notation${label}Median", median)
        macro("Notation${label}Max", counts.last())
    }
}

/**
 * The headline numbers as LaTeX macros, so the paper's text quotes the run rather
 * than a copy of it. Use with `\input{generated/step-reuse-numbers.tex}`.
 */
private fun latexNumbers(inline: Strategy, library: Strategy): String = buildString {
    appendLine("% GENERATED by StepReuseEval - do not edit; run :carp.dsp.demo:evalStepReuse")
    fun macro(name: String, value: Int) = appendLine("\\newcommand{\\$name}{$value}")
    macro("StepReuseWorkflows", LIBRARY_WORKFLOWS.size)
    macro("StepReuseDistinctSteps", library.distinct)
    macro("StepReuseReferences", library.total)
    macro("StepReuseRepeatsAvoided", library.repeatsAvoided)
    macro("StepReuseInlineSteps", inline.totalInline)
    macro("StepReuseInlineLines", inline.authoredLines)
    macro("StepReuseLibraryLines", library.authoredLines)
    macro("StepReuseInlineDuplicatedLines", inline.duplicatedLines)
    // Composition from the library: steps written out rather than referenced, and
    // the YAML each reference takes (wiring only).
    macro("StepReuseLibraryInlineSteps", library.totalInline)
    macro("StepReuseWiringLinesPerUse", Math.round(library.authoredLines.toDouble() / library.total).toInt())
}

/**
 * Count references and declaration size per step, in first-appearance order.
 *
 * The row order is derived rather than hardcoded: a fixed list silently produces a
 * table of zeros when a step is renamed or removed, which is how the previous
 * version of this eval came to describe steps that no longer existed.
 */
private fun measure(
    name: String,
    workflows: List<Pair<String, String>>,
    restatesDeclaration: Boolean,
    identify: (String) -> String?,
): Strategy {
    val references = LinkedHashMap<String, MutableList<String>>()
    val lines = LinkedHashMap<String, Int>()
    val order = mutableListOf<String>()
    val stepsPerWorkflow = LinkedHashMap<String, Int>()

    for ((label, resource) in workflows) {
        val blocks = stepBlocks(DemoIo.loadResource(resource))
        stepsPerWorkflow[label] = blocks.size
        blocks.forEach { (_, block) ->
            val id = identify(block) ?: return@forEach
            if (id !in order) order += id
            references.getOrPut(id) { mutableListOf() }.add(label)
            lines[id] = maxOf(lines[id] ?: 0, block.lines().count { it.isNotBlank() })
        }
    }
    return Strategy(
        name = name,
        references = references,
        declarationLines = lines,
        stepOrder = order,
        stepsPerWorkflow = stepsPerWorkflow,
        restatesDeclaration = restatesDeclaration,
    )
}

/** `core.io.fetch-zenodo` -> `fetch-zenodo`; `sensing.steps.clean` -> `steps.clean`. */
private fun shortLabel(id: String): String {
    val parts = id.split('.')
    return if (parts.firstOrNull() == "core") parts.last() else parts.takeLast(2).joinToString(".")
}

private fun table(s: Strategy): String = buildString {
    appendLine("  %-20s %-38s %s".format("library step", "workflows", "refs"))
    s.stepOrder.forEach { id ->
        val uses = s.references[id].orEmpty()
        appendLine("  %-20s %-38s %d".format(id, uses.distinct().joinToString(","), uses.size))
    }
    appendLine("  %-20s %-38s %d".format("REFERENCES", "", s.total))
    append("  %-20s %-38s %d".format("REPEATS AVOIDED", "", s.repeatsAvoided))
}

/**
 * The whole reuse tabular, so the paper table is generated rather than transcribed.
 *
 * The complete `tabular` is emitted rather than only its body rows: `\input`
 * *inside* an alignment fails, because a `\midrule` or `\bottomrule` across the
 * file boundary becomes a misplaced `\noalign`. Emitting the environment also
 * keeps the column headings tied to the data rather than to a hand-kept list.
 *
 * Cells use `\Uc{colour}{n}`, defined by the paper: a tick for one reference, a
 * count for more, so a step referenced twice in one workflow is visible as such.
 */
private fun latexTable(s: Strategy): String = buildString {
    val columns = COLUMN_COLOUR.keys.toList()
    appendLine("% GENERATED by StepReuseEval - do not edit; run :carp.dsp.demo:evalStepReuse")
    appendLine("\\begin{tabular}{@{}l ccc c@{}}")
    appendLine("\\toprule")
    val headings = columns.joinToString(" & ") { column ->
        "\\shortstack{${column.substringBefore('-').replaceFirstChar { it.uppercase() }}\\\\${column.substringAfter('-')}}"
    }
    appendLine("Library step & $headings & Refs \\\\")
    appendLine("\\midrule")
    s.stepOrder.forEach { id ->
        val uses = s.references[id].orEmpty()
        val cells = columns.joinToString(" & ") { column ->
            val n = uses.count { it == column }
            if (n == 0) "" else "\\Uc{${COLUMN_COLOUR[column]}}{$n}"
        }
        appendLine("\\texttt{$id} & $cells & $${uses.size}$ \\\\")
    }
    appendLine("\\midrule")
    // Steps per workflow against distinct library steps: the gap between the two is
    // the reuse. The paper caption states that every step is a library reference.
    appendLine(
        "Steps in workflow & ${columns.joinToString(" & ") { "${s.stepsIn(it)}" }} & $${s.totalSteps}$ \\\\"
    )
    val distinct = columns.joinToString(" & ") { column ->
        "${s.stepOrder.count { id -> column in s.references[id].orEmpty() }}"
    }
    appendLine("Distinct library steps & $distinct & $${s.stepOrder.size}$ \\\\")
    appendLine("Repeats avoided & \\multicolumn{3}{c}{} & $${s.repeatsAvoided}$ \\\\")
    appendLine("\\bottomrule")
    appendLine("\\end{tabular}")
}

private fun csv(inline: Strategy, library: Strategy): String = buildString {
    appendLine("strategy,library_step,references,workflows,declaration_lines")
    listOf(inline, library).forEach { s ->
        s.stepOrder.forEach { id ->
            val uses = s.references[id].orEmpty()
            appendLine("${s.name},$id,${uses.size},${uses.distinct().joinToString("|")},${s.declarationLines[id]}")
        }
    }
    appendLine()
    appendLine("strategy,distinct,references,repeats_avoided,authored_lines,duplicated_lines")
    listOf(inline, library).forEach { s ->
        appendLine("${s.name},${s.distinct},${s.total},${s.repeatsAvoided},${s.authoredLines},${s.duplicatedLines}")
    }
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
