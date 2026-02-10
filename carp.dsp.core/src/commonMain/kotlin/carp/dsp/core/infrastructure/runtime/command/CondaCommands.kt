package carp.dsp.core.infrastructure.runtime.command

import dk.cachet.carp.analytics.application.runtime.Command

/**
 * Pure builder for conda-related commands. Produces structured [Command] instances
 * without invoking any shell wrappers.
 */
class CondaCommands {

    /**
     * List all conda environments.
     */
    fun envList(timeoutMs: Long? = null): Command = Command(
        exe = "conda",
        args = listOf("env", "list"),
        cwd = null,
        env = emptyMap(),
        stdin = null,
        timeoutMs = timeoutMs
    )

    /**
     * Create a conda environment with optional python version, channels, and packages.
     */
    fun createEnv(
        name: String,
        pythonVersion: String? = null,
        channels: List<String> = emptyList(),
        packages: List<String> = emptyList(),
        timeoutMs: Long? = null
    ): Command {
        val args = mutableListOf("create", "-n", name)
        channels.forEach { channel ->
            args += "-c"
            args += channel
        }
        args += (pythonVersion?.let { "python=$it" } ?: "python")
        if (packages.isNotEmpty()) args += packages
        args += "--yes"

        return Command(
            exe = "conda",
            args = args,
            cwd = null,
            env = emptyMap(),
            stdin = null,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Install packages into an existing environment.
     */
    fun installPackages(
        envName: String,
        packages: List<String>,
        channels: List<String> = emptyList(),
        timeoutMs: Long? = null
    ): Command {
        val args = mutableListOf("run", "-n", envName, "conda", "install")
        channels.forEach { channel ->
            args += "-c"
            args += channel
        }
        args += packages
        args += "-y"

        return Command(
            exe = "conda",
            args = args,
            cwd = null,
            env = emptyMap(),
            stdin = null,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Run an executable inside a given environment using `conda run`.
     */
    fun runInEnv(
        envName: String,
        exe: String,
        args: List<String> = emptyList(),
        cwd: String? = null,
        envVars: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        timeoutMs: Long? = null
    ): Command = Command(
        exe = "conda",
        args = listOf("run", "-n", envName, exe) + args,
        cwd = cwd,
        env = envVars,
        stdin = stdin,
        timeoutMs = timeoutMs
    )
}