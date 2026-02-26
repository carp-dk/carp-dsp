package carp.dsp.core.infrastructure.runtime.command

import dk.cachet.carp.analytics.application.plan.CommandSpec

/**
 * Pure builder for conda-related commands.
 */
class CondaCommands {

    fun envList(): CommandSpec =
        CommandSpec(
            executable = "conda",
            args = listOf("env", "list")
        )

    fun createEnv(
        name: String,
        pythonVersion: String? = null,
        channels: List<String> = emptyList(),
        packages: List<String> = emptyList()
    ): CommandSpec {
        val args = mutableListOf("create", "-n", name)

        channels.forEach { channel ->
            args += "-c"
            args += channel
        }

        args += (pythonVersion?.let { "python=$it" } ?: "python")

        if (packages.isNotEmpty()) args += packages
        args += "--yes"

        return CommandSpec(executable = "conda", args = args)
    }

    fun installPackages(
        envName: String,
        packages: List<String>,
        channels: List<String> = emptyList()
    ): CommandSpec {
        val args = mutableListOf("run", "-n", envName, "conda", "install")

        channels.forEach { channel ->
            args += "-c"
            args += channel
        }

        args += packages
        args += "-y"

        return CommandSpec(executable = "conda", args = args)
    }

    fun runInEnv(
        envName: String,
        exe: String,
        args: List<String> = emptyList()
    ): CommandSpec =
        CommandSpec(
            executable = "conda",
            args = listOf("run", "-n", envName, exe) + args
        )
}
