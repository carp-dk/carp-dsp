package carp.dsp.core.infrastructure.execution

import java.nio.file.Path

private const val RUNTIME_JAR = "carp-task-runtime.jar"

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
     * Defaults beside the provisioned environments. Assignable so a test can
     * point at a directory it controls.
     */
    var directory: Path = defaultDirectory()

    /** The installed runtime jar. */
    val jar: Path get() = directory.resolve(RUNTIME_JAR)

    /** Returns [argument] with [TOKEN] replaced by the runtime jar's path. */
    fun substitute(argument: String): String =
        if (TOKEN in argument) argument.replace(TOKEN, jar.toString()) else argument

    private fun defaultDirectory(): Path =
        Path.of(System.getProperty("user.home"), ".carp-dsp", "task-runtime")
}
