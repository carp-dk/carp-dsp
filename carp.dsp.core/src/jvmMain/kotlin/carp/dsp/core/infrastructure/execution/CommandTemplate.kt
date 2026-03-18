package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.EnvironmentRef

/**
 * Command generation from environment template.
 *
 * Encapsulates the logic of expanding {executable} and {args}
 * into the final command string.
 */
data class CommandTemplate(
    val environmentRef: EnvironmentRef,
    val executable: String,
    val args: List<String>
) {

    /**
     * Expand template into final command string.
     *
     * Example:
     * Input: environmentRef=CondaRef("myenv"),
     *        executable="python",
     *        args=["script.py", "arg1"]
     * Output: "conda run -n myenv python script.py arg1"
     */
    fun toCommandString(): String {
        val template = environmentRef.generateExecutionTemplate()
        return template
            .replace("{executable}", executable)
            .replace("{args}", args.joinToString(" "))
            .trimEnd()
    }

    /**
     * Get as bash command for ProcessBuilder.
     */
    fun toBashCommand(): List<String> {
        return listOf("bash", "-c", toCommandString())
    }
}
