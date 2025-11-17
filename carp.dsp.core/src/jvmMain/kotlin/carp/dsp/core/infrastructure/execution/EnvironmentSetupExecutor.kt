package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.domain.execution.ExecutionContext

/**
 * Handles the setup and validation of execution environments (conda, venv, etc.).
 *
 * This class should eventually be moved to carp.analytics.core as generic infrastructure.
 * Location: dk.cachet.carp.analytics.infrastructure.execution.EnvironmentSetupExecutor
 *
 * Currently simplified to handle conda environments. Can be extended for other environment types.
 */
class EnvironmentSetupExecutor {

    private val processExecutor = ProcessExecutor()

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
     * Ensures conda environment exists using an Environment object.
     * Extracts dependencies from the Environment and automatically creates if missing.
     */
    fun ensureCondaEnvironment(
        environment: dk.cachet.carp.analytics.domain.environment.Environment,
        createIfMissing: Boolean = true
    ): Boolean {
        val envName = environment.name
        val dependencies = environment.dependencies

        // Try to extract pythonVersion and channels by accessing fields
        var pythonVersion: String? = null
        var channels: List<String> = emptyList()

        try {
            val envClass = environment::class.java

            // Try to get pythonVersion field
            try {
                val pythonVersionField = envClass.getDeclaredField("pythonVersion")
                pythonVersionField.isAccessible = true
                pythonVersion = pythonVersionField.get(environment) as? String
            } catch (_: Exception) {
                // Field doesn't exist
            }

            // Try to get channels field
            try {
                val channelsField = envClass.getDeclaredField("channels")
                channelsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                channels = channelsField.get(environment) as? List<String> ?: emptyList()
            } catch (_: Exception) {
                // Field doesn't exist
            }
        } catch (_: Exception) {
            // Could not access fields, use defaults
        }

        if (pythonVersion != null || channels.isNotEmpty()) {
            println("Extracted from Environment: pythonVersion=$pythonVersion, channels=$channels")
        }

        return ensureCondaEnvironment(envName, createIfMissing, dependencies, pythonVersion, channels)
    }

    /**
     * Checks if a conda environment exists by parsing the output of 'conda env list'.
     */
    fun condaEnvironmentExists(envName: String): Boolean {
        return try {
            val result = processExecutor.executeCommandSafe("conda env list")

            if (result.exitCode == 0) {
                // Parse text output
                // Format is either:
                // env_name    /path/to/env
                // or contains the env name in the path
                return result.output.lines().any { line ->
                    val trimmedLine = line.trim()
                    // Check if line starts with env name (active or not)
                    trimmedLine.startsWith(envName + " ") ||
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
        } catch (e: Exception) {
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

            // Build command similar to old CondaCommandGenerator
            val baseCommand = "conda create -n $envName"

            // Add python version if specified
            val pythonCommand = pythonVersion?.let { "python=$it" } ?: "python"

            // Add channels
            val channelCommand = channels.joinToString(" ") { "-c $it" }

            // Add conda dependencies
            val condaDependencyCommand = condaPackages.joinToString(" ")

            // Always include pip if we have pip packages
            val pipCommand = if (pipPackages.isNotEmpty()) "pip" else ""

            // Combine all parts, filter out blanks
            val command = listOf(
                baseCommand,
                channelCommand,
                pythonCommand,
                pipCommand,
                condaDependencyCommand,
                "--yes"
            ).filter { it.isNotBlank() }.joinToString(" ")

            println("Creating conda environment...")
            println("Executing: $command")

            val result = processExecutor.executeCommandSafe(command)
            if (result.exitCode != 0) {
                println("✗ Failed to create conda environment '$envName'")
                println("Exit code: ${result.exitCode}")
                println("Output: ${result.output}")
                return false
            }

            println("✓ Successfully created conda environment '$envName'")

            // Install pip packages if any
            if (pipPackages.isNotEmpty()) {
                return installPipPackages(envName, pipPackages)
            }

            true
        } catch (e: Exception) {
            println("✗ Failed to create conda environment '$envName': ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Installs pip packages in a conda environment.
     */
    private fun installPipPackages(envName: String, packages: List<String>): Boolean {
        return try {
            val packageList = packages.joinToString(" ")
            val command = "conda run -n $envName pip install $packageList"

            println("Installing pip packages in '$envName'...")
            println("Executing: $command")

            val result = processExecutor.executeCommandSafe(command)
            if (result.exitCode != 0) {
                println("✗ Failed to install pip packages")
                println("Exit code: ${result.exitCode}")
                println("Output: ${result.output}")

                // Try alternative: activate and pip install
                println("Trying alternative method: python -m pip install...")
                val altCommand = "conda run -n $envName python -m pip install $packageList"
                println("Executing: $altCommand")

                val altResult = processExecutor.executeCommandSafe(altCommand)
                if (altResult.exitCode != 0) {
                    println("✗ Alternative method also failed")
                    println("Output: ${altResult.output}")
                    return false
                }
            }

            println("✓ Successfully installed pip packages: ${packages.joinToString(", ")}")
            true
        } catch (e: Exception) {
            println("✗ Failed to install pip packages: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Activates a conda environment and executes a command within it.
     *
     * @param envName Name of the conda environment
     * @param command Command to execute in the environment
     * @param envVariables Additional environment variables
     * @return The output from the command
     */
    fun executeInCondaEnvironment(
        envName: String,
        command: String,
        envVariables: Map<String, String> = emptyMap()
    ): String {
        val fullCommand = "conda run -n $envName $command"
        return processExecutor.executeCommand(fullCommand, envVariables)
    }

    /**
     * Validates environment setup for a given execution context.
     * This is the main entry point that should be called during setup phase.
     */
    fun validateEnvironment(context: ExecutionContext): Boolean {
        val environment = context.environment ?: return true // No environment specified

        return when {
            environment.name.isNotBlank() -> {
                // Assume conda environment for now
                ensureCondaEnvironment(environment.name, createIfMissing = false)
            }
            else -> true
        }
    }

    /**
     * Gets information about available conda environments.
     * Returns a list of environment names.
     */
    fun listCondaEnvironments(): List<String> {
        return try {
            val result = processExecutor.executeCommandSafe("conda env list")

            if (result.exitCode == 0) {
                // Parse lines to extract environment names
                result.output.lines()
                    .filter { it.trim().isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        // Format: "envname   /path/to/env" or "* envname   /path/to/env"
                        val parts = trimmed.split(Regex("\\s+"))
                        when {
                            parts.isEmpty() -> null
                            parts[0] == "*" && parts.size > 1 -> parts[1] // Active env
                            parts[0] != "base" && parts[0] != "#" -> parts[0]
                            else -> null
                        }
                    }
                    .distinct()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("Warning: Failed to list conda environments: ${e.message}")
            emptyList()
        }
    }
}

