package carp.dsp.core.application.process

import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.application.data.InMemoryData
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import dk.cachet.carp.analytics.domain.process.ExternalProcess
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A process that executes Python scripts using conda environments.
 *
 * This process supports:
 * - Running Python scripts in isolated conda environments
 * - Passing arguments to scripts
 * - Handling in-memory data via stdin
 * - Automatic input/output binding
 *
 * @param name The name of the process.
 * @param description Optional description of what the process does.
 * @param executionContext Execution context including environment configuration.
 * @param scriptPath Path to the Python script to execute.
 * @param arguments Optional command-line arguments for the script.
 * @param pythonExecutable The Python executable to use (default: "python").
 * @param useCondaRun Whether to use "conda run" (true) or direct python execution (false).
 */
class PythonProcess(
    override val name: String,
    override val description: String = "",
    override val executionContext: ExecutionContext,
    val scriptPath: String,
    val arguments: List<String> = emptyList(),
    val pythonExecutable: String = "python",
    val useCondaRun: Boolean = true
) : ExternalProcess {

    companion object {
        /**
         * If false, skips the script path existence check.
         * Useful in test contexts or when script will be created dynamically.
         */
        var validateScriptPath: Boolean = true

        /**
         * Creates a new builder for PythonProcess.
         */
        fun builder(name: String) = Builder(name)
    }

    /**
     * Buffer for stdin data (in-memory inputs will be serialized here).
     */
    private val stdinBuffer = StringBuilder()

    /**
     * Additional arguments that will be added during binding resolution.
     */
    private val dynamicArguments = mutableListOf<String>()

    init {
        require(scriptPath.isNotBlank()) { "Script path cannot be empty" }
        require(arguments.none { it.isBlank() }) { "Arguments cannot contain blank strings" }

        if (validateScriptPath) {
            validateScriptPath()
        }
    }

    /**
     * Validates that the script file exists.
     */
    private fun validateScriptPath() {
        val path = Path.of(scriptPath)
        require(path.exists()) { "Script path does not exist: $scriptPath" }
    }

    /**
     * Gets all arguments including static and dynamically added ones.
     */
    override fun getArguments(): Any {
        return buildMap {
            put("script", scriptPath)
            put("arguments", arguments + dynamicArguments)
            put("stdin", stdinBuffer.toString())
            put("pythonExecutable", pythonExecutable)
            put("useCondaRun", useCondaRun)
        }
    }

    /**
     * Gets the stdin buffer content.
     */
    fun getStdinBuffer(): String = stdinBuffer.toString()

    /**
     * Generates the full command to execute the Python script.
     *
     * Format:
     * - With conda: `conda run -n <env> python <script> <args>`
     * - Without conda: `python <script> <args>`
     */
    fun getFormattedCommand(): String {
        val allArgs = (arguments + dynamicArguments).joinToString(" ")

        return if (useCondaRun) {
            val environmentName = executionContext.environment?.name
                ?: error("Environment must be specified when using conda run")
            "conda run -n $environmentName $pythonExecutable $scriptPath $allArgs".trim()
        } else {
            "$pythonExecutable $scriptPath $allArgs".trim()
        }
    }

    /**
     * Resolves input and output data bindings from the new data model.
     *
     * This method:
     * 1. Handles in-memory inputs by serializing them to stdin
     * 2. Handles file inputs by adding --input arguments
     * 3. Handles outputs by adding --output arguments
     *
     * @param inputs List of input data specifications
     * @param outputs List of output data specifications
     * @param dataRegistry Registry to resolve in-memory data
     */
    fun resolveBindings(
        inputs: List<InputDataSpec>,
        outputs: List<OutputDataSpec>,
        dataRegistry: DataRegistry
    ) {
        // Clear previous bindings
        dynamicArguments.clear()
        stdinBuffer.clear()

        // Resolve inputs
        inputs.forEach { inputSpec ->
            when (val source = inputSpec.source) {
                is InMemorySource -> {
                    // Resolve in-memory data and add to stdin buffer
                    val dataHandle = dataRegistry.resolve(source.registryKey)
                    if (dataHandle is InMemoryData) {
                        // Serialize data to JSON or CSV format
                        val serialized = serializeData(dataHandle.dataset)
                        if (stdinBuffer.isNotEmpty()) {
                            stdinBuffer.append("\n")
                        }
                        stdinBuffer.append(serialized)
                    } else {
                        error("In-memory data not found for key: ${source.registryKey}")
                    }
                }
                is FileSystemSource -> {
                    // Add file path as argument
                    dynamicArguments.add("--input")
                    dynamicArguments.add(source.path)
                }
                else -> {
                    throw IllegalArgumentException("Unsupported input source type: ${source::class.simpleName}")
                }
            }
        }

        // Resolve outputs
        outputs.forEach { outputSpec ->
            when (val destination = outputSpec.destination) {
                is FileDestination -> {
                    // Add output file path as argument
                    dynamicArguments.add("--output")
                    dynamicArguments.add(destination.path)
                }
                is RegistryDestination -> {
                    // For registry destinations, script should write to stdout
                    // which will be captured and stored in registry
                    dynamicArguments.add("--output")
                    dynamicArguments.add("-")  // "-" indicates stdout
                }
                else -> {
                    throw IllegalArgumentException("Unsupported output destination type: ${destination::class.simpleName}")
                }
            }
        }
    }

    /**
     * Serializes data for stdin transmission.
     * Override this method to customize serialization format.
     */
    protected open fun serializeData(data: Any): String {
        // Default: Convert to string representation
        // In production, you'd use JSON/CSV serialization based on data type
        return when (data) {
            is String -> data
            else -> data.toString()
        }
    }

    /**
     * Builder for creating PythonProcess instances.
     */
    class Builder(private val name: String) {
        private var description: String = ""
        private var executionContext: ExecutionContext? = null
        private var scriptPath: String? = null
        private var arguments: List<String> = emptyList()
        private var pythonExecutable: String = "python"
        private var useCondaRun: Boolean = true

        fun description(description: String) = apply { this.description = description }
        fun executionContext(context: ExecutionContext) = apply { this.executionContext = context }
        fun scriptPath(path: String) = apply { this.scriptPath = path }
        fun arguments(vararg args: String) = apply { this.arguments = args.toList() }
        fun pythonExecutable(executable: String) = apply { this.pythonExecutable = executable }
        fun useCondaRun(use: Boolean) = apply { this.useCondaRun = use }

        fun build(): PythonProcess {
            require(scriptPath != null) { "Script path must be specified" }
            require(executionContext != null) { "Execution context must be specified" }

            return PythonProcess(
                name = name,
                description = description,
                executionContext = executionContext!!,
                scriptPath = scriptPath!!,
                arguments = arguments,
                pythonExecutable = pythonExecutable,
                useCondaRun = useCondaRun
            )
        }
    }
}

