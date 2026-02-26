package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import carp.dsp.core.infrastructure.runtime.command.CondaCommands
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import java.io.IOException

/**
 * Handles the setup and validation of execution environments (conda, venv, etc.).
 *
 * P0: Execute consumes CommandSpec directly; policies are provided via CommandPolicy.
 */
class EnvironmentSetupExecutor(
    private val commandRunner: CommandRunner = JvmCommandRunner(),
    private val condaCommands: CondaCommands = CondaCommands()
) {

    fun ensureCondaEnvironment(
        envName: String,
        createIfMissing: Boolean = false,
        dependencies: List<String> = emptyList(),
        pythonVersion: String? = null,
        channels: List<String> = emptyList()
    ): Boolean {
        if (condaEnvironmentExists(envName)) {
            println("✓ Conda environment '$envName' exists.")
            return true
        }

        if (!createIfMissing) {
            println("Warning: Conda environment '$envName' does not exist.")
            return false
        }

        println("Creating conda environment '$envName'...")
        return createCondaEnvironment(envName, dependencies, pythonVersion, channels)
    }

    fun condaEnvironmentExists(envName: String): Boolean {
        return try {
            val result = runConda(condaCommands.envList(), CommandPolicy(timeoutMs = 10_000))

            if (result.exitCode != 0) return false

            result.stdout.lines().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("$envName ") ||
                        trimmed.startsWith("* $envName ") ||
                        trimmed.contains("/$envName") ||
                        trimmed.contains("\\$envName") ||
                        trimmed.endsWith("\\envs\\$envName") ||
                        trimmed.endsWith("/envs/$envName")
            }
        } catch (e: IOException) {
            println("Warning: Failed to check conda environments: ${e.message}")
            false
        }
    }

    private fun createCondaEnvironment(
        envName: String,
        dependencies: List<String> = emptyList(),
        pythonVersion: String? = null,
        channels: List<String> = emptyList()
    ): Boolean {
        return try {
            val condaPackages = mutableListOf<String>()
            val pipPackages = mutableListOf<String>()

            dependencies.forEach { dep ->
                if (dep.startsWith("pip:")) pipPackages += dep.removePrefix("pip:")
                else condaPackages += dep
            }

            val createResult = runConda(
                condaCommands.createEnv(
                    name = envName,
                    pythonVersion = pythonVersion,
                    channels = channels,
                    packages = condaPackages + if (pipPackages.isNotEmpty()) listOf("pip") else emptyList()
                ),
                CommandPolicy(timeoutMs = 10_000)
            )

            if (createResult.exitCode != 0) {
                println("✗ Failed to create conda environment '$envName'")
                println("Exit code: ${createResult.exitCode}")
                println("Stdout: ${createResult.stdout}")
                println("Stderr: ${createResult.stderr}")
                return false
            }

            println("✓ Successfully created conda environment '$envName'")

            if (pipPackages.isNotEmpty()) installPipPackages(envName, pipPackages) else true
        } catch (e: IOException) {
            println("✗ Failed to create conda environment '$envName': ${e.message}")
            false
        }
    }

    private fun installPipPackages(envName: String, packages: List<String>): Boolean {
        return try {
            val result = runConda(
                condaCommands.runInEnv(envName, exe = "pip", args = listOf("install") + packages),
                CommandPolicy(timeoutMs = 10_000)
            )

            if (result.exitCode != 0) {
                println("✗ Failed to install pip packages (pip)")
                println("Exit code: ${result.exitCode}")
                println("Stdout: ${result.stdout}")
                println("Stderr: ${result.stderr}")

                println("Trying alternative method: python -m pip install...")
                val alt = runConda(
                    condaCommands.runInEnv(envName, exe = "python", args = listOf("-m", "pip", "install") + packages),
                    CommandPolicy(timeoutMs = 10_000)
                )

                if (alt.exitCode != 0) {
                    println("✗ Alternative method also failed")
                    println("Stdout: ${alt.stdout}")
                    println("Stderr: ${alt.stderr}")
                    return false
                }
            }

            println("✓ Successfully installed pip packages: ${packages.joinToString(", ")}")
            true
        } catch (e: IOException) {
            println("✗ Failed to install pip packages: ${e.message}")
            false
        }
    }

    private fun runConda(command: CommandSpec, policy: CommandPolicy): CommandResult =
        commandRunner.run(command, policy)
}
