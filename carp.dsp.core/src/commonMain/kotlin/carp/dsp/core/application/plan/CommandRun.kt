package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

/**
 * Execute an external command as a structured, tokenized command.
 *
 * Invariants:
 * - [executable] must not be blank.
 * - [args] are already tokenized.
 * - [envVars] are explicit and merged by the runner/executor.
 */
@Serializable
data class CommandRun(
    val executable: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val stdin: ByteArray? = null,
    val timeoutMs: Long? = null,
) : ProcessRun {

    // Defensive copies to prevent shared mutable references.
    val safeArgs: List<String> = args.toList()
    val safeEnvVars: Map<String, String> = envVars.toMap()

    init {
        require(executable.isNotBlank()) { "CommandRun.executable must not be blank." }
        cwd?.let { require(it.isNotBlank()) { "CommandRun.cwd must not be blank when provided." } }
        timeoutMs?.let { require(it > 0) { "CommandRun.timeoutMs must be > 0 when provided." } }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandRun) return false

        if (executable != other.executable) return false
        if (args != other.args) return false
        if (cwd != other.cwd) return false
        if (envVars != other.envVars) return false
        if (timeoutMs != other.timeoutMs) return false

        val a = stdin
        val b = other.stdin
        if (a == null && b != null) return false
        if (a != null && b == null) return false
        if (a != null && !a.contentEquals(b)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = executable.hashCode()
        result = 31 * result + args.hashCode()
        result = 31 * result + (cwd?.hashCode() ?: 0)
        result = 31 * result + envVars.hashCode()
        result = 31 * result + (stdin?.contentHashCode() ?: 0)
        result = 31 * result + (timeoutMs?.hashCode() ?: 0)
        return result
    }
}
