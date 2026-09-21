package carp.dsp.core.infrastructure.execution

import java.nio.file.Path

private const val RUNTIME_JAR = "carp-task-runtime.jar"

/** Names the directory the runtime is installed in, when it is not the default. */
const val TASK_RUNTIME_VARIABLE: String = "CARP_DSP_TASK_RUNTIME"

/**
 * The classpath library steps written in Kotlin run against.
 *
 * A step's arguments carry [TOKEN] where the classpath belongs. The executor
 * substitutes [jar] while building the command, so a step never names a path
 * that depends on the machine it runs on.
 */
object TaskRuntime
{
    /** Placeholder a step's arguments use in place of the runtime classpath. */
    const val TOKEN: String = "{carp.taskRuntime}"

    /**
     * Where the runtime is installed.
     *
     * [TASK_RUNTIME_VARIABLE] when it is set, and beside the provisioned
     * environments otherwise. Assignable so a test can point at a directory it
     * controls.
     */
    var directory: Path = defaultDirectory()

    /** The installed runtime jar. */
    val jar: Path get() = directory.resolve(RUNTIME_JAR)

    /** Returns [argument] with [TOKEN] replaced by the runtime jar's path. */
    fun substitute(argument: String): String =
        if (TOKEN in argument) argument.replace(TOKEN, jar.toString()) else argument

    /** Returns the directory [environment] names, or the one beside the provisioned environments. */
    internal fun defaultDirectory(environment: Map<String, String> = System.getenv()): Path =
        // A container keeps its home on a volume, which outlives the image: a jar
        // installed there would survive the rebuild that should have replaced it.
        // The variable lets an image carry its own runtime outside that volume.
        environment[TASK_RUNTIME_VARIABLE]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".carp-dsp", "task-runtime")
}
