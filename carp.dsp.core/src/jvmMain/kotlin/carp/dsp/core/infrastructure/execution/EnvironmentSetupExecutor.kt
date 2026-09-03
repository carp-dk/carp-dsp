package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import carp.dsp.core.infrastructure.runtime.command.CondaCommands
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException

/**
 * Handles the setup and validation of execution environments (conda, venv, etc.).
 */
class EnvironmentSetupExecutor(
    private val commandRunner: CommandRunner = JvmCommandRunner(),
    private val condaCommands: CondaCommands = CondaCommands()
) {
    private val logger = KotlinLogging.logger {}

    fun ensureCondaEnvironment(
        envName: String,
        createIfMissing: Boolean = false,
        dependencies: List<String> = emptyList(),
        pythonVersion: String? = null,
        channels: List<String> = emptyList()
    ): Boolean {
        if (condaEnvironmentExists(envName)) {
            logger.debug { "Conda environment '$envName' exists" }
            return true
        }

        if (!createIfMissing) {
            logger.warn { "Conda environment '$envName' does not exist and was not created" }
            return false
        }

        logger.info { "Creating conda environment '$envName'" }
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
            logger.warn(e) { "Could not list conda environments" }
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
                logger.error { "Creating conda environment '$envName' failed. ${createResult.describe()}" }
                return false
            }

            logger.info { "Created conda environment '$envName'" }

            if (pipPackages.isNotEmpty()) installPipPackages(envName, pipPackages) else true
        } catch (e: IOException) {
            logger.error(e) { "Creating conda environment '$envName' failed" }
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
                logger.warn { "'pip install' failed, retrying with 'python -m pip'. ${result.describe()}" }

                val alt = runConda(
                    condaCommands.runInEnv(envName, exe = "python", args = listOf("-m", "pip", "install") + packages),
                    CommandPolicy(timeoutMs = 10_000)
                )

                if (alt.exitCode != 0) {
                    logger.error { "Installing pip packages in '$envName' failed. ${alt.describe()}" }
                    return false
                }
            }

            logger.info { "Installed pip packages in '$envName': ${packages.joinToString(", ")}" }
            true
        } catch (e: IOException) {
            logger.error(e) { "Installing pip packages in '$envName' failed" }
            false
        }
    }

    private fun runConda(command: CommandSpec, policy: CommandPolicy): CommandResult =
        commandRunner.run(command, policy)

    /** Exit code and both streams on one line, so a failure is one log entry. */
    private fun CommandResult.describe(): String =
        "Exit code $exitCode. stdout: ${stdout.trim()} stderr: ${stderr.trim()}"
}
