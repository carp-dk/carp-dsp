package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import carp.dsp.core.infrastructure.runtime.command.CondaCommands
import dk.cachet.carp.analytics.application.runtime.Command
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import java.io.IOException

/**
 * Handles the setup and validation of execution environments (conda, venv, etc.).
 *
 * This class should eventually be moved to carp.analytics.core as generic infrastructure.
 * Location: dk.cachet.carp.analytics.infrastructure.execution.EnvironmentSetupExecutor
 *
 * Currently simplified to handle conda environments. Can be extended for other environment types.
 */
class EnvironmentSetupExecutor {

    private val commandRunner: CommandRunner = JvmCommandRunner()
    private val condaCommands = CondaCommands()

    /**
     * Validates that a conda environment exists.
     * Creates it if missing and creation is requested.
     *
     * @param envName Name of the conda environment
     * @param createIfMissing Whether to create the environment if it doesn't exist
     * @param dependencies List of packages/dependencies to install when creating
     * @param pythonVersion Python version to use (e.g., "3.11")
     * @param channels Additional conda channels (e.g., ["conda-forge"])
     * @return true if environment exists or was created successfully
     */
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

    /**
     * Checks if a conda environment exists by parsing the output of 'conda env list'.
     */
    fun condaEnvironmentExists(envName: String): Boolean {
        return try {
            val result = runConda(condaCommands.envList())

            if (result.exitCode == 0) {
                // Parse text output
                // Format is either:
                // env_name    /path/to/env
                // or contains the env name in the path
                return result.stdout.lines().any { line ->
                    val trimmedLine = line.trim()
                    // Check if line starts with env name (active or not)
                    trimmedLine.startsWith("$envName ") ||
                    trimmedLine.startsWith("* $envName ") || // Active environment marked with *
                    // Check if env name appears in path
                    trimmedLine.contains("/$envName") ||
                    trimmedLine.contains("\\$envName") ||
                    // Windows conda format: path ends with envs\envname
                    trimmedLine.endsWith("\\envs\\$envName") ||
                    trimmedLine.endsWith("/envs/$envName")
                }
            }

            false
        } catch (e: IOException) {
            println("Warning: Failed to check conda environments: ${e.message}")
            false
        }
    }

    /**
     * Creates a conda environment.
     *
     * @param envName Name of the environment
     * @param dependencies List of packages/dependencies to install
     * @param pythonVersion Python version (e.g., "3.11")
     * @param channels Additional conda channels (e.g., ["conda-forge"])
     */
    private fun createCondaEnvironment(
        envName: String,
        dependencies: List<String> = emptyList(),
        pythonVersion: String? = null,
        channels: List<String> = emptyList()
    ): Boolean {
        return try {
            // Separate conda packages from pip packages
            val condaPackages = mutableListOf<String>()
            val pipPackages = mutableListOf<String>()

            dependencies.forEach { dep ->
                if (dep.startsWith("pip:")) {
                    // pip package: "pip:cgmquantify"
                    pipPackages.add(dep.removePrefix("pip:"))
                } else {
                    // conda package: "pandas"
                    condaPackages.add(dep)
                }
            }

            val result = runConda(
                condaCommands.createEnv(
                    name = envName,
                    pythonVersion = pythonVersion,
                    channels = channels,
                    packages = condaPackages + if (pipPackages.isNotEmpty()) listOf("pip") else emptyList()
                )
            )
            if (result.exitCode != 0) {
                println("✗ Failed to create conda environment '$envName'")
                println("Exit code: ${result.exitCode}")
                println("Stdout: ${result.stdout}")
                println("Stderr: ${result.stderr}")
                return false
            }

            println("✓ Successfully created conda environment '$envName'")

            // Install pip packages if any
            if (pipPackages.isNotEmpty()) {
                return installPipPackages(envName, pipPackages)
            }

            true
        } catch (e: IOException) {
            println("✗ Failed to create conda environment '$envName': ${e.message}")
            false
        }
    }

    /**
     * Installs pip packages in a conda environment.
     */
    private fun installPipPackages(envName: String, packages: List<String>): Boolean {
        return try {
            val result = runConda(
                condaCommands.runInEnv(
                    envName = envName,
                    exe = "pip",
                    args = listOf("install") + packages
                )
            )
            if (result.exitCode != 0) {
                println("✗ Failed to install pip packages")
                println("Exit code: ${result.exitCode}")
                println("Stdout: ${result.stdout}")
                println("Stderr: ${result.stderr}")

                // Try alternative: activate and pip install
                println("Trying alternative method: python -m pip install...")
                val altResult = runConda(
                    condaCommands.runInEnv(
                        envName = envName,
                        exe = "python",
                        args = listOf("-m", "pip", "install") + packages
                    )
                )
                if (altResult.exitCode != 0) {
                    println("✗ Alternative method also failed")
                    println("Stdout: ${altResult.stdout}")
                    println("Stderr: ${altResult.stderr}")
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

    private fun runConda(command: Command): CommandResult = commandRunner.run(command)
}
