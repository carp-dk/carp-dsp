package carp.dsp.core.testing

import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner

/**
 * Test implementation of the core [CommandRunner] interface.
 *
 * Register scripted responses with [on] or [onThrow] before exercising a handler.
 * Commands are matched by joining `executable + args` with spaces and calling
 * [String.startsWith], so register a prefix rather than the full command to handle
 * dynamic parts such as environment names or file paths.
 *
 * Any command not matched by a registered prefix throws [IllegalStateException]
 * immediately, surfacing unexpected invocations rather than silently swallowing them.
 *
 * ## Usage
 * ```kotlin
 * val mock = MockCommandRunner().apply {
 *     on("conda --version")                          // exit 0, empty output
 *     on("conda create", exitCode = 1, stderr = "SolverError")
 *     onThrow("conda env list", IOException("not found"))
 * }
 * val handler = CondaEnvironmentHandler(mock)
 * ```
 *
 * ## Working directory
 * The `workingDir` passed by handlers that use [carp.dsp.core.infrastructure.runtime.JvmCommandRunner]'s workspace-root
 * overload is NOT visible through [CommandRunner.run]. If you need to assert that
 * a specific working directory was used (e.g. for `pixi install`), use [capturedSpecs]
 * to inspect the [CommandSpec] and verify the executable + args, then confirm the
 * side effect separately (e.g. check that a file was written to the expected directory).
 */
class MockCommandRunner : CommandRunner {

    private sealed class Action {
        data class Return(val result: CommandResult) : Action()
        data class Throw(val ex: Exception) : Action()
    }

    private val responses = mutableListOf<Pair<String, Action>>()

    /** All [CommandSpec]s received in invocation order. */
    val capturedSpecs = mutableListOf<CommandSpec>()

    /** Convenience view: each captured call as `"executable arg1 arg2 ..."`. */
    val capturedCommands: List<String>
        get() = capturedSpecs.map { spec ->
            listOf(spec.executable)
                .plus(
                    spec.args.map { arg ->
                    when (arg) {
                        is ExpandedArg.Literal -> arg.value
                        is ExpandedArg.DataReference -> arg.id.toString()
                        is ExpandedArg.PathSubstitution -> arg.template
                        is ExpandedArg.EnvironmentVariable -> arg.template
                    }
                }
                )
                .joinToString(" ")
        }

    /**
     * Registers a scripted [CommandResult] for any command whose string representation
     * starts with [cmdPrefix].
     */
    fun on(
        cmdPrefix: String,
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
    ) {
        responses.add(
            cmdPrefix to Action.Return(
                CommandResult(exitCode = exitCode, stdout = stdout, stderr = stderr, durationMs = 0, timedOut = false)
            )
        )
    }

    /**
     * Registers an exception to be thrown for any command whose string representation
     * starts with [cmdPrefix]. Use this to exercise `catch(IOException)` and similar
     * branches that are unreachable when a command merely returns a non-zero exit code.
     */
    fun onThrow(cmdPrefix: String, ex: Exception) {
        responses.add(cmdPrefix to Action.Throw(ex))
    }

    override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
        capturedSpecs.add(command)

        val commandStr = listOf(command.executable)
            .plus(
                command.args.map { arg ->
                when (arg) {
                    is ExpandedArg.Literal -> arg.value
                    is ExpandedArg.DataReference -> arg.id.toString()
                    is ExpandedArg.PathSubstitution -> arg.template
                    is ExpandedArg.EnvironmentVariable -> arg.template
                }
            }
            )
            .joinToString(" ")

        val action = responses.firstOrNull { (prefix, _) -> commandStr.startsWith(prefix) }?.second
            ?: error(
                "MockCommandRunner: unexpected command: \"$commandStr\"\n" +
                        "Registered prefixes: ${responses.map { "\"${it.first}\"" }}"
            )

        return when (action) {
            is Action.Return -> action.result
            is Action.Throw -> throw action.ex
        }
    }
}
