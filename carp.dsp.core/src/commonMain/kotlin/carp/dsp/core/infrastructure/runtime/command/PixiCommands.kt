package carp.dsp.core.infrastructure.runtime.command

import carp.dsp.core.application.environment.PixiEnvironment
import dk.cachet.carp.analytics.application.runtime.Command

data class PixiRunOptions(
    val cwd: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val stdin: ByteArray? = null,
    val timeoutMs: Long? = null
)

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
        environment: PixiEnvironment,
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
        environment: PixiEnvironment,
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
