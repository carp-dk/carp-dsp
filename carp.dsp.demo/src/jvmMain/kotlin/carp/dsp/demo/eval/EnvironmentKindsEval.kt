package carp.dsp.demo.eval

import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.MobgapScripts
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.common.application.UUID
import java.util.Locale

/**
 * EX1 eval: one single-step run per environment kind that runs (pixi, conda, R, system).
 *
 * Each run copies a CSV through a script in that kind of environment, the way a real run
 * does (prepare, provision, execute). Per kind it records whether the run succeeded, its
 * wall time (first run includes environment setup), and what the run recorded about the
 * environment under `environments/` in the run folder. A fifth run, in the system
 * environment, also writes a file outside its own step folder; the run must flag it.
 *
 *   ./gradlew :carp.dsp.demo:evalEnvironmentKinds -PpaperDir=../carp-dsp-sys-paper
 *
 * Needs pixi, conda, Rscript and python on PATH; a missing tool shows as a failed row.
 * Writes eval_results/environment-kinds.csv, environment-kinds-table.tex and
 * environment-kinds-numbers.tex.
 */
fun main()
{
    // Scratch under build/ (not committed): the workflows and their run folders.
    val work = DemoIo.projectRoot().resolve( "build/environment-kinds" ).apply { deleteRecursively(); mkdirs() }
    val executor = WorkflowExecutor.filesystem( ClasspathStepLibrary(), work.resolve( "runs" ).toPath() )

    val rows = cases().map { case ->
        val dir = work.resolve( case.id ).apply { mkdirs() }
        dir.resolve( "workflow.yaml" ).writeText( case.yaml )
        dir.resolve( "scripts" ).mkdirs()
        dir.resolve( "scripts/copy.py" ).writeText( COPY_PY )
        dir.resolve( "scripts/copy.R" ).writeText( COPY_R )
        dir.resolve( "data" ).mkdirs()
        dir.resolve( "data/input.csv" ).writeText( "x,y\n1,2\n3,4\n" )

        val runId = UUID.randomUUID()
        val t0 = System.nanoTime()
        val prepared = executor.prepare( WorkflowPackage.of( dir.toPath(), "workflow.yaml" ) )
        val report = executor.run( prepared, runId )
        val seconds = ( System.nanoTime() - t0 ) / 1e9

        val runRoot = MobgapScripts.runRoot( work.resolve( "runs" ).toPath(), prepared.plan, runId ).toFile()
        val record = runRoot.resolve( "environments" ).listFiles().orEmpty().firstOrNull()
        val issues = report.issues
        KindRow(
            case = case,
            succeeded = report.status == ExecutionStatus.SUCCEEDED,
            seconds = seconds,
            recordedFrom = record?.readLines()?.firstOrNull { it.startsWith( "recorded from:" ) }
                ?.substringAfter( ":" )?.trim() ?: "nothing",
            recordLines = record?.readText()?.substringAfter( "---\n", "" )?.lines()?.count { it.isNotBlank() } ?: 0,
            outsideWriteFlagged = issues.any {
                it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT && it.message.contains( "wrote outside" )
            },
            failure = report.issues.firstOrNull { report.status != ExecutionStatus.SUCCEEDED }?.message.orEmpty()
        )
    }

    val out = DemoIo.evalResultsDir()
    out.resolve( "environment-kinds.csv" ).writeText( csv( rows ) )
    val table = out.resolve( "environment-kinds-table.tex" ).apply { writeText( table( rows ) ) }
    val numbers = out.resolve( "environment-kinds-numbers.tex" ).apply { writeText( numbers( rows ) ) }
    println( report( rows ) )
    DemoIo.publishToPaper( table, "generated/environment-kinds-table.tex" )
    DemoIo.publishToPaper( numbers, "generated/environment-kinds-numbers.tex" )
}

private data class KindCase( val id: String, val kind: String, val yaml: String, val leaks: Boolean = false )

private data class KindRow(
    val case: KindCase,
    val succeeded: Boolean,
    val seconds: Double,
    val recordedFrom: String,
    val recordLines: Int,
    val outsideWriteFlagged: Boolean,
    val failure: String
)

private fun cases(): List<KindCase> = listOf(
    KindCase( "pixi", "pixi", workflow( "pixi", ENV_PIXI, PYTHON_TASK ) ),
    KindCase( "conda", "conda", workflow( "conda", ENV_CONDA, PYTHON_TASK ) ),
    KindCase( "r", "R", workflow( "r", envR( installedRVersion() ), R_TASK ) ),
    KindCase( "system", "system", workflow( "system", ENV_SYSTEM, PYTHON_TASK ) ),
    KindCase( "system-leak", "system", workflow( "system-leak", ENV_SYSTEM, PYTHON_TASK_LEAK ), leaks = true )
)

/** The installed R's major.minor, which an author would declare; "0.0" when R is missing. */
private fun installedRVersion(): String =
    runCatching {
        val p = ProcessBuilder( "Rscript", "--version" ).redirectErrorStream( true ).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex( """(\d+\.\d+)""" ).find( text )?.value
    }.getOrNull() ?: "0.0"

private fun workflow( id: String, environment: String, task: String ) = """
    |schemaVersion: "1.0"
    |metadata:
    |  id: "env-kinds-$id"
    |  name: "Environment kinds - $id"
    |  version: "1.0"
    |environments:
    |$environment
    |steps:
    |  - id: "copy"
    |    metadata:
    |      name: "Copy CSV"
    |      version: "1.0"
    |    environmentId: "env"
    |$task
    |    inputs:
    |      - id: "input-csv"
    |        descriptor:
    |          fileFormat: "csv"
    |        source:
    |          type: "file"
    |          path: "data/input.csv"
    |    outputs:
    |      - id: "output-csv"
    |        descriptor:
    |          fileFormat: "csv"
    |""".trimMargin()

private val ENV_PIXI = """
    |  env:
    |    name: "carp-dsp-eval-pixi"
    |    kind: "pixi"
    |    spec:
    |      pythonVersion: ["3.12"]
    |      channels: ["conda-forge"]
""".trimMargin()

private val ENV_CONDA = """
    |  env:
    |    name: "carp-dsp-eval-conda"
    |    kind: "conda"
    |    spec:
    |      pythonVersion: ["3.12"]
    |      channels: ["conda-forge"]
""".trimMargin()

private fun envR( version: String ) = """
    |  env:
    |    name: "carp-dsp-eval-r"
    |    kind: "r"
    |    spec:
    |      rVersion: ["$version"]
""".trimMargin()

private val ENV_SYSTEM = """
    |  env:
    |    name: "system"
    |    kind: "system"
    |    spec: {}
""".trimMargin()

private fun pythonTask( vararg extra: String ) = """
    |    task:
    |      type: "python"
    |      name: "copy"
    |      entryPoint:
    |        type: "script"
    |        scriptPath: "scripts/copy.py"
    |      args: ["input.0", "output.0"${extra.joinToString( "" ) { ", \"$it\"" }}]
""".trimMargin()

private val PYTHON_TASK = pythonTask()
private val PYTHON_TASK_LEAK = pythonTask( "--leak" )

private val R_TASK = """
    |    task:
    |      type: "r"
    |      name: "copy"
    |      entryPoint:
    |        type: "r-script"
    |        scriptPath: "scripts/copy.R"
    |      args: ["input.0", "output.0"]
""".trimMargin()

private const val COPY_PY = """import pathlib, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
pathlib.Path(dst).parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(src, dst)
if "--leak" in sys.argv:
    # State left in the shared run folder, where another step could read it.
    pathlib.Path("leak.txt").write_text("left for another step\n")
"""

private const val COPY_R = """a <- commandArgs(trailingOnly = TRUE)
dir.create(dirname(a[2]), recursive = TRUE, showWarnings = FALSE)
invisible(file.copy(a[1], a[2], overwrite = TRUE))
"""

private fun csv( rows: List<KindRow> ): String = buildString {
    appendLine( "case,kind,succeeded,seconds,recorded_from,record_lines,outside_write_flagged,failure" )
    rows.forEach {
        appendLine(
            listOf(
                it.case.id, it.case.kind, it.succeeded, "%.1f".format( Locale.ROOT, it.seconds ),
                it.recordedFrom, it.recordLines, it.outsideWriteFlagged, it.failure.replace( ",", ";" ).take( 200 )
            ).joinToString( "," )
        )
    }
}

/**
 * Rows of the per-kind table, Kind & Ran & Recorded from (the leak run is a separate macro);
 * the `tabular` is the paper's. Wall time stays in the CSV: it depends on what is cached.
 */
private fun table( rows: List<KindRow> ): String = buildString {
    appendLine( "% GENERATED by EnvironmentKindsEval - do not edit; run :carp.dsp.demo:evalEnvironmentKinds" )
    rows.filterNot { it.case.leaks }.forEach {
        val ran = if ( it.succeeded ) "yes" else "no"
        val recorded = it.recordedFrom.replace( "_", "\\_" ).replace( "--", "-{}-" )
        appendLine( "${it.case.kind} & $ran & $recorded \\\\" )
    }
}

private fun numbers( rows: List<KindRow> ): String = buildString {
    val kinds = rows.filterNot { it.case.leaks }
    appendLine( "% GENERATED by EnvironmentKindsEval - do not edit; run :carp.dsp.demo:evalEnvironmentKinds" )
    appendLine( "\\newcommand{\\EnvKinds}{${kinds.size}}" )
    appendLine( "\\newcommand{\\EnvKindsRan}{${kinds.count { it.succeeded }}}" )
    appendLine( "\\newcommand{\\EnvKindsRecorded}{${kinds.count { it.recordLines > 0 }}}" )
    appendLine( "\\newcommand{\\EnvOutsideWriteFlagged}{${if ( rows.any { it.case.leaks && it.outsideWriteFlagged } ) "yes" else "no"}}" )
    appendLine( "\\newcommand{\\EnvCleanRunsFlagged}{${kinds.count { it.outsideWriteFlagged }}}" )
}

private fun report( rows: List<KindRow> ): String = buildString {
    appendLine( "=".repeat( 78 ) )
    appendLine( "Environment kinds eval (EX1) - ${java.time.Instant.now()}" )
    appendLine( "  %-12s %-7s %-4s %7s  %-45s %s".format( "case", "kind", "ran", "seconds", "recorded from", "outside write" ) )
    rows.forEach {
        appendLine(
            "  %-12s %-7s %-4s %7.1f  %-45s %s".format(
                Locale.ROOT, it.case.id, it.case.kind, if ( it.succeeded ) "yes" else "no", it.seconds,
                "${it.recordedFrom} (${it.recordLines} lines)", if ( it.outsideWriteFlagged ) "flagged" else "-"
            )
        )
        if ( it.failure.isNotEmpty() ) appendLine( "      ${it.failure.take( 160 )}" )
    }
    appendLine( "=".repeat( 78 ) )
}
