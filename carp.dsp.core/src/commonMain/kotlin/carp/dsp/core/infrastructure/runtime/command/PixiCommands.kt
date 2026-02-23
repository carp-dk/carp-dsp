package carp.dsp.core.infrastructure.runtime.command

import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.analytics.application.plan.CommandSpec

/**
 * Pure builder for pixi-related commands.
 *
 * P0: returns only CommandSpec (executable + args).
 * Pixi expects workingDirectory to be the project dir (pixi.toml); pass via CommandPolicy.
 */
class PixiCommands {

    fun install(features: List<String> = emptyList()): CommandSpec {
        val args = mutableListOf("install")
        if (features.isNotEmpty()) {
            args += "--features"
            args.addAll(features)
        }

        return CommandSpec(executable = "pixi", args = args)
    }

    fun run(
        environment: PixiEnvironmentDefinition,
        exe: String,
        args: List<String> = emptyList()
    ): CommandSpec =
        CommandSpec(
            executable = "pixi",
            args = listOf("run", "-e", environment.name, exe) + args
        )
}
