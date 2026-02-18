package carp.dsp.core.infrastructure.runtime.command

import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.analytics.application.runtime.Command

data class PixiRunOptions(
    val cwd: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val stdin: ByteArray? = null,
    val timeoutMs: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PixiRunOptions

        if (timeoutMs != other.timeoutMs) return false
        if (cwd != other.cwd) return false
        if (envVars != other.envVars) return false
        if (!stdin.contentEquals(other.stdin)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timeoutMs?.hashCode() ?: 0
        result = 31 * result + (cwd?.hashCode() ?: 0)
        result = 31 * result + envVars.hashCode()
        result = 31 * result + (stdin?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Pure builder for pixi-related commands. Produces structured [Command] instances
 * without any shell wrappers.
 */
@Suppress("unused")
class PixiCommands {

    /**
     * Install/resolve dependencies for the given pixi environment.
     * Optionally enable features.
     */
    fun install(
        environment: PixiEnvironmentDefinition,
        features: List<String> = emptyList(),
        cwd: String? = null,
        timeoutMs: Long? = null
    ): Command {
        val args = mutableListOf("install")
        if (features.isNotEmpty()) {
            args += "--features"
            args.addAll(features)
        }

        return Command(
            exe = "pixi",
            args = args,
            cwd = cwd,
            env = emptyMap(),
            stdin = null,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Run an executable within the pixi environment using `pixi run`.
     */
    fun run(
        environment: PixiEnvironmentDefinition,
        exe: String,
        args: List<String> = emptyList(),
        options: PixiRunOptions = PixiRunOptions()
    ): Command = Command(
        exe = "pixi",
        args = listOf("run", "-e", environment.name, exe) + args,
        cwd = options.cwd,
        env = options.envVars,
        stdin = options.stdin,
        timeoutMs = options.timeoutMs
    )
}
