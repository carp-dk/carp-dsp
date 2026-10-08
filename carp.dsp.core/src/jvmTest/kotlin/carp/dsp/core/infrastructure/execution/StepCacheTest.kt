package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.authoring.resolve.StepLibrary
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs real two-step workflows twice and counts how often each step's command ran.
 *
 * `copy` copies a file the bundle carries; `count` counts its bytes. Each script
 * appends a line to a counter file, so a reused step leaves no line.
 */
class StepCacheTest
{
    private val root: Path = Files.createTempDirectory( "step-cache" )
    private val counters: Path = root.resolve( "counters" ).createDirectories()
    private val cache = StepCache( root.resolve( "cache" ) )

    private fun bundle( name: String, copyCache: Boolean? = null ): Path
    {
        val dir = root.resolve( name ).createDirectories()
        dir.resolve( "scripts" ).createDirectories()
        dir.resolve( "data" ).createDirectories()
        dir.resolve( "scripts/copy.sh" ).writeText( "echo run >> '$counters/copy'\ncp \"$1\" \"$2\"\n" )
        dir.resolve( "scripts/count.sh" ).writeText( "echo run >> '$counters/count'\nwc -c < \"$1\" > \"$2\"\n" )
        dir.resolve( "scripts/join.sh" ).writeText( "echo run >> '$counters/join'\ncat \"$1\" \"$2\" > \"$3\"\n" )
        dir.resolve( "data/in.txt" ).writeText( "hello\n" )
        val cacheLine = copyCache?.let { "    cache: $it\n" } ?: ""
        dir.resolve( "workflow.yaml" ).writeText(
            """
            |schemaVersion: "1.0"
            |metadata:
            |  id: "$name"
            |  name: "$name"
            |  version: "1.0"
            |environments:
            |  env-system:
            |    name: "system"
            |    kind: "system"
            |    spec: {}
            |steps:
            |  - id: "copy-$name"
            |    metadata:
            |      name: "Copy"
            |      version: "1.0"
            |    environmentId: "env-system"
            |    dependsOn: []
            |$cacheLine    task:
            |      type: "command"
            |      id: "task-copy"
            |      name: "copy"
            |      executable: "sh"
            |      args: ["scripts/copy.sh", "input.0", "output.0"]
            |    inputs:
            |      - id: "raw"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |        source: { type: "file", path: "data/in.txt" }
            |    outputs:
            |      - id: "copied"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |  - id: "count-$name"
            |    metadata:
            |      name: "Count"
            |      version: "1.0"
            |    environmentId: "env-system"
            |    dependsOn: ["copy-$name"]
            |    task:
            |      type: "command"
            |      id: "task-count"
            |      name: "count"
            |      executable: "sh"
            |      args: ["scripts/count.sh", "input.0", "output.0"]
            |    inputs:
            |      - id: "copied"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |        source: { type: "step-output", stepId: "copy-$name", outputId: "copied" }
            |    outputs:
            |      - id: "size"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |  - id: "join-$name"
            |    metadata:
            |      name: "Join"
            |      version: "1.0"
            |    environmentId: "env-system"
            |    dependsOn: ["copy-$name", "count-$name"]
            |    task:
            |      type: "command"
            |      id: "task-join"
            |      name: "join"
            |      executable: "sh"
            |      args: ["scripts/join.sh", "input.0", "input.1", "output.0"]
            |    inputs:
            |      - id: "copied"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |        source: { type: "step-output", stepId: "copy-$name", outputId: "copied" }
            |      - id: "size"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            |        source: { type: "step-output", stepId: "count-$name", outputId: "size" }
            |    outputs:
            |      - id: "joined"
            |        descriptor: { fileFormat: "txt", encoding: "utf-8" }
            """.trimMargin()
        )
        return dir
    }

    private fun run( bundle: Path, withCache: StepCache? = cache ): ExecutionReport
    {
        val executor = WorkflowExecutor.filesystem(
            stepLibrary = StepLibrary { _, _ -> null },
            workspaceRoot = root.resolve( "runs" ),
            options = WorkflowExecutor.Options(
                executorOptions = DefaultPlanExecutor.Options( stepCache = withCache ),
            ),
        )
        return executor.run( executor.prepare( WorkflowPackage.of( bundle, "workflow.yaml" ) ) )
    }

    private fun runs( counter: String ): Int =
        counters.resolve( counter ).takeIf { it.exists() }?.readLines()?.size ?: 0

    private fun reused( report: ExecutionReport ): List<Boolean> =
        report.stepResults.map { it.detail?.metrics?.get( StepCache.REUSED_METRIC ) == 1.0 }

    @Test
    fun `a second run reuses every step`()
    {
        val dir = bundle( "same" )

        val first = run( dir )
        val second = run( dir )

        assertEquals( ExecutionStatus.SUCCEEDED, second.status )
        assertEquals( listOf( false, false, false ), reused( first ) )
        assertEquals( listOf( true, true, true ), reused( second ) )
        assertEquals( 1, runs( "copy" ) )
        assertEquals( 1, runs( "count" ) )
        assertEquals( 1, runs( "join" ) )
        assertEquals( 3, second.stepResults.sumOf { it.outputs.orEmpty().size } )
    }

    @Test
    fun `a changed input runs the steps again`()
    {
        val dir = bundle( "changed" )
        run( dir )

        dir.resolve( "data/in.txt" ).writeText( "hello again\n" )
        val second = run( dir )

        assertEquals( listOf( false, false, false ), reused( second ) )
        assertEquals( 2, runs( "copy" ) )
        assertEquals( 2, runs( "count" ) )
    }

    @Test
    fun `a changed script runs its step again`()
    {
        val dir = bundle( "script" )
        run( dir )

        dir.resolve( "scripts/count.sh" ).writeText( "echo run >> '$counters/count'\nwc -l < \"$1\" > \"$2\"\n" )
        val second = run( dir )

        // The scripts share a folder, and a script may read its neighbours, so both run.
        assertEquals( listOf( false, false, false ), reused( second ) )
    }

    @Test
    fun `a step marked cache false runs every time, and what it feeds is reused when its output is unchanged`()
    {
        val dir = bundle( "source", copyCache = false )

        run( dir )
        val second = run( dir )

        assertEquals( listOf( false, true, true ), reused( second ) )
        assertEquals( 2, runs( "copy" ) )
        assertEquals( 1, runs( "count" ) )
    }

    @Test
    fun `another workflow running the same steps on the same data shares the entries`()
    {
        run( bundle( "one" ) )
        val other = run( bundle( "two" ) )

        assertEquals( listOf( true, true, true ), reused( other ) )
    }

    @Test
    fun `without a cache every step runs`()
    {
        val dir = bundle( "off" )

        run( dir, withCache = null )
        run( dir, withCache = null )

        assertEquals( 2, runs( "copy" ) )
    }

    @Test
    fun `a reused output holds what the step wrote, and its log says where it came from`()
    {
        val dir = bundle( "content" )
        run( dir )

        val second = run( dir )
        val sizes = Files.walk( root.resolve( "runs" ) ).use { paths ->
            paths.filter { it.fileName.toString().startsWith( "size" ) }.toList()
        }

        assertEquals( 2, sizes.size )
        sizes.forEach { assertEquals( "6", it.readText().trim() ) }

        val log = second.stepResults[1].detail?.stdout?.value
        assertTrue( log != null && log.endsWith( "-reused.log" ) )
    }

    @Test
    fun `entries beyond the size are removed, oldest first`()
    {
        // Each entry holds a few bytes, so a two-byte cache keeps none for long.
        val small = StepCache( root.resolve( "small-cache" ), maxBytes = 2 )
        val dir = bundle( "trimmed" )

        run( dir, withCache = small )
        val second = run( dir, withCache = small )

        assertEquals( listOf( false, false, false ), reused( second ) )
        assertEquals( 2, runs( "count" ) )
    }
}
