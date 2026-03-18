package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Handles Pixi environment setup, execution, and teardown.
 *
 * @param runner The core [CommandRunner] used for process invocations.
 *   Defaults to [JvmCommandRunner] for production.
 *
 */
class PixiEnvironmentHandler(
    private val runner: CommandRunner = JvmCommandRunner()
) : EnvironmentHandler {

    private val defaultPolicy = CommandPolicy()

    override fun canHandle(environmentRef: EnvironmentRef): Boolean =
        environmentRef is PixiEnvironmentRef

    override fun setup(environmentRef: EnvironmentRef): Boolean {
        val pixi = environmentRef as PixiEnvironmentRef

        return try {
            check(verifyPixiInstalled()) { "Pixi not found. Install pixi and add to PATH." }

            val projectDir = getProjectDirectory(pixi.id)
            projectDir.createDirectories()

            generatePixiToml(projectDir, pixi)

            check(installPixiEnvironment(projectDir)) { "pixi install failed" }

            check(validate(pixi)) { "Environment created but validation failed" }

            true
        } catch (e: IllegalStateException) {
            throw EnvironmentProvisioningException("Pixi setup failed: ${e.message}", e)
        } catch (e: IOException) {
            throw EnvironmentProvisioningException("Pixi setup failed: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EnvironmentProvisioningException("Pixi setup interrupted", e)
        }
    }

    override fun generateExecutionCommand(environmentRef: EnvironmentRef, command: String): String =
        "pixi run $command"

    override fun teardown(environmentRef: EnvironmentRef): Boolean {
        val pixi = environmentRef as PixiEnvironmentRef

        return try {
            val projectDir = getProjectDirectory(pixi.id)
            if (projectDir.toFile().exists()) {
                projectDir.toFile().deleteRecursively()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun validate(environmentRef: EnvironmentRef): Boolean {
        val pixi = environmentRef as PixiEnvironmentRef

        return try {
            val projectDir = getProjectDirectory(pixi.id)
            if (!projectDir.toFile().exists()) return false

            val pythonExe = findPythonExecutable(projectDir) ?: return false
            if (!pythonExe.toFile().exists()) return false

            runCommand(pythonExe.toString()).exitCode == 0
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun verifyPixiInstalled(): Boolean = try {
        runCommand("pixi").exitCode == 0
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun getProjectDirectory(envId: String): Path =
        Path.of(System.getProperty("user.home"), ".carp-dsp", "envs", "pixi", envId)

    private fun generatePixiToml(projectDir: Path, pixi: PixiEnvironmentRef) {
        val dependenciesStr = pixi.dependencies.joinToString(
            separator = "\n    ",
            prefix = "\n    ",
            postfix = "\n"
        ) { "\"$it\"" }

        val tomlContent = """
            [project]
            name = "carp-dsp-env"
            version = "0.1.0"
            description = "CARP-DSP Pixi Environment"
            
            [dependencies]
            python = "${pixi.pythonVersion}"$dependenciesStr
            
            [tasks]
        """.trimIndent()

        projectDir.resolve("pixi.toml").writeText(tomlContent)
    }

    private fun installPixiEnvironment(projectDir: Path): Boolean = try {
        // `pixi install` must run with the project directory as its working directory.
        // See class KDoc for why we use JvmCommandRunner's workspace-root overload here.
        val spec = CommandSpec(
            executable = "pixi",
            args = listOf(ExpandedArg.Literal("install"))
        )
        val result = when (val r = runner) {
            is JvmCommandRunner -> r.run(spec, defaultPolicy, projectDir)
            else -> runner.run(spec, defaultPolicy) // MockCommandRunner captures workingDir separately
        }
        result.exitCode == 0
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun findPythonExecutable(projectDir: Path): Path? =
        listOf(
            projectDir.resolve(".pixi/envs/default/bin/python"),
            projectDir.resolve(".pixi/envs/default/bin/python3"),
            projectDir.resolve(".pixi/envs/default/Scripts/python.exe")
        ).find { it.toFile().exists() }

    private fun runCommand(executable: String): CommandResult =
        runner.run(
            CommandSpec(executable = executable, args = listOf(ExpandedArg.Literal("--version")) ),
            defaultPolicy
        )
}
