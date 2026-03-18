package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler

/**
 * Handles Conda environment setup, execution, and teardown.
 *
 * @param runner The core [CommandRunner] used for all process invocations.
 *   Defaults to [JvmCommandRunner] for production.
 */
class CondaEnvironmentHandler(
    private val runner: CommandRunner = JvmCommandRunner()
) : EnvironmentHandler {

    private val defaultPolicy = CommandPolicy()

    override fun canHandle(environmentRef: EnvironmentRef): Boolean =
        environmentRef is CondaEnvironmentRef

    override fun setup(environmentRef: EnvironmentRef): Boolean {
        val conda = environmentRef as CondaEnvironmentRef

        if (!verifyCondaInstalled()) {
            throw EnvironmentProvisioningException(
                "Conda not found. Install conda and add to PATH."
            )
        }

        val createSuccess = createCondaEnvironment(
            name = conda.name,
            pythonVersion = conda.pythonVersion,
            channels = conda.channels,
            dependencies = conda.dependencies
        )
        if (!createSuccess) {
            throw EnvironmentProvisioningException(
                "Failed to create conda environment: ${conda.name}"
            )
        }

        if (!validate(conda)) {
            throw EnvironmentProvisioningException(
                "Environment created but validation failed"
            )
        }

        return true
    }

    override fun generateExecutionCommand(environmentRef: EnvironmentRef, command: String): String {
        val conda = environmentRef as CondaEnvironmentRef
        return "conda run -n ${conda.name} $command"
    }

    override fun teardown(environmentRef: EnvironmentRef): Boolean {
        val conda = environmentRef as CondaEnvironmentRef

        return try {
            // Mirrors conda's own behaviour: `conda env remove` exits 0 silently when
            // the environment does not exist. teardown's postcondition is "the environment
            // does not exist", which is already satisfied whether we removed it or it
            // was never there. -y suppresses the interactive confirmation prompt in CI.
            runCommand("env", "remove", "-n", conda.name, "-y").exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun validate(environmentRef: EnvironmentRef): Boolean {
        val conda = environmentRef as CondaEnvironmentRef

        return try {
            if (!condaEnvironmentExists(conda.name)) return false

            if (runCommand("run", "-n", conda.name, "python", "--version").exitCode != 0)
                return false

            for (dep in conda.dependencies) {
                val moduleName = dep.split("/")[0].split("=")[0]
                if (runCommand("run", "-n", conda.name, "python", "-c", "import $moduleName").exitCode != 0)
                    return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun verifyCondaInstalled(): Boolean = try {
        runCommand("--version").exitCode == 0
    } catch (_: Exception) {
        false
    }

    private fun condaEnvironmentExists(envName: String): Boolean {
        return try {
            val result = runCommand("env", "list")
            if (result.exitCode != 0) return false

            result.stdout.lines().any { line ->
                val t = line.trim()
                t.startsWith("$envName ") ||
                t.startsWith("* $envName ") ||
                t.contains("/$envName") ||
                t.contains("\\$envName")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createCondaEnvironment(
        name: String,
        pythonVersion: String,
        channels: List<String>,
        dependencies: List<String>
    ): Boolean = try {
        val condaPackages = mutableListOf<String>()
        val pipPackages = mutableListOf<String>()
        for (dep in dependencies) {
            if (dep.startsWith("pip:")) pipPackages.add(dep.removePrefix("pip:"))
            else condaPackages.add(dep)
        }

        val createArgs = mutableListOf("create", "-n", name, "-y", "python=$pythonVersion")
        for (channel in channels) {
            createArgs.add("-c")
        createArgs.add(channel)
        }
        createArgs.addAll(condaPackages)
        if (pipPackages.isNotEmpty()) createArgs.add("pip")

        val createResult = runCommand(createArgs)
        if (createResult.exitCode != 0) {
            throw CondaCommandExecutionException("conda create failed:\n${createResult.stderr}")
        }

        if (pipPackages.isNotEmpty()) {
            val pipCmd = listOf("run", "-n", name, "pip", "install") + pipPackages
            val pipResult = runCommand(pipCmd)
            if (pipResult.exitCode != 0) {
                throw CondaCommandExecutionException("pip install failed:\n${pipResult.stderr}")
            }
        }

        true
    } catch (_: Exception) {
        false
    }

    /**
     * Builds a [CommandSpec] with all-[ExpandedArg.Literal] arguments and runs it
     * via [runner] with [defaultPolicy].
     *
     * All conda management commands are plain strings — no data-reference or
     * path-substitution tokens are ever needed here.
     */
    private fun runCommand(args: List<String>): CommandResult =
        runner.run(
            CommandSpec(executable = "conda", args = args.map { ExpandedArg.Literal(it) }),
            defaultPolicy
        )

    private fun runCommand(vararg args: String): CommandResult = runCommand(args.toList())
}

class CondaCommandExecutionException(message: String) : IllegalStateException(message)
