package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
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
private const val NAME_PUNCTUATION = "._-"

/**
 * Returns [dependency] as a pixi requirement line.
 */
internal fun pixiRequirement(dependency: String): String {
    val name = dependency.takeWhile { it.isLetterOrDigit() || it in NAME_PUNCTUATION }
    val rest = dependency.drop(name.length).trim()

    // A single '=' is how a catalogue entry pins an exact version, but pixi reads
    // it as part of the constraint, so it is dropped. Range operators are passed
    // through as written.
    val constraint = when {
        rest.isEmpty() -> "*"
        rest.startsWith("==") || rest.startsWith(">=") || rest.startsWith("<=") -> rest
        rest.startsWith("=") -> rest.removePrefix("=")
        else -> rest
    }.trim().ifEmpty { "*" }

    return "$name = \"$constraint\""
}

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

            val projectDir = getProjectDirectory(pixi)
            projectDir.createDirectories()

            generatePixiToml(projectDir, pixi)

            installPixiEnvironment(projectDir)

            check(validate(pixi)) { "Environment created but validation failed" }

            // Only write manifest if successful
            EnvironmentStore.writeManifest(projectDir, pixi)

            true
        } catch (e: IllegalStateException) {
            throw EnvironmentSetupException(
                message = "Failed to provision environment: ${pixi.name}",
                envId = pixi.id,
                cause = e
            )
        } catch (e: IOException) {
            throw EnvironmentSetupException(
                message = "Failed to setup failed: ${pixi.name}",
                envId = pixi.id,
                cause = e
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EnvironmentSetupException(
                message = "Pixi setup interrupted: ${pixi.name}",
                envId = pixi.id,
                cause = e
            )
        }
    }

    override fun generateExecutionCommand(environmentRef: EnvironmentRef, command: String): String {
        val pixi = environmentRef as PixiEnvironmentRef
        val manifest = getProjectDirectory(pixi).resolve("pixi.toml")
        return "pixi run --manifest-path \"$manifest\" $command"
    }

    override fun teardown(environmentRef: EnvironmentRef): Boolean {
        val pixi = environmentRef as PixiEnvironmentRef

        return try {
            val projectDir = getProjectDirectory(pixi)
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
            val projectDir = getProjectDirectory(pixi)
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

    /** See [EnvironmentStore]: the directory is what the environment is, not what it is called. */
    private fun getProjectDirectory(pixi: PixiEnvironmentRef): Path =
        EnvironmentStore.resolve(pixi)?.directory
            ?: error("No provisioned directory for environment '${pixi.name}'")

    private fun generatePixiToml(projectDir: Path, pixi: PixiEnvironmentRef) {
        val channels = pixi.channels.joinToString(", ") { "\"$it\"" }

        // Partition dependencies into conda (default) vs PyPI (prefixed with "pypi:")
        val (pypiDeps, condaDeps) = pixi.dependencies.partition { it.startsWith("pypi:") }

        val condaSection = buildString {
            appendLine("python = \"${pixi.pythonVersion}.*\"")
            condaDeps.forEach { appendLine(pixiRequirement(it)) }
        }

        val pypiSection = if (pypiDeps.isNotEmpty()) buildString {
            pypiDeps.forEach { dep -> appendLine(pixiRequirement(dep.removePrefix("pypi:"))) }
        } else null

        val tomlContent = buildString {
            appendLine("[project]")
            appendLine("name = \"carp-dsp-env\"")
            appendLine("version = \"0.1.0\"")
            appendLine("channels = [$channels]")
            appendLine("platforms = [\"${currentPlatform()}\"]")
            appendLine()
            appendLine("[dependencies]")
            append(condaSection)
            if (pypiSection != null) {
                appendLine()
                appendLine("[pypi-dependencies]")
                append(pypiSection)
            }
            appendLine()
            appendLine("[tasks]")
        }

        projectDir.resolve("pixi.toml").writeText(tomlContent)
    }

    private fun currentPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("win") -> if (arch.contains("aarch64")) "win-arm64" else "win-64"
            os.contains("mac") -> if (arch.contains("aarch64")) "osx-arm64" else "osx-64"
            else -> if (arch.contains("aarch64")) "linux-aarch64" else "linux-64"
        }
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
        if (result.exitCode != 0) {
            val detail = listOfNotNull(
                result.stderr.takeIf { it.isNotBlank() },
                result.stdout.takeIf { it.isNotBlank() }
            ).joinToString("\n")
            val msg = "pixi install failed (exit ${result.exitCode})" +
                if (detail.isNotBlank()) ":\n$detail" else ""
            throw IllegalStateException(msg)
        }
        true
    } catch (e: IllegalStateException) {
        throw e
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun findPythonExecutable(projectDir: Path): Path? =
        listOf(
            projectDir.resolve(".pixi/envs/default/python.exe"), // Windows (conda-style root)
            projectDir.resolve(".pixi/envs/default/bin/python"), // Linux/macOS
            projectDir.resolve(".pixi/envs/default/bin/python3"), // Linux/macOS fallback
            projectDir.resolve(".pixi/envs/default/Scripts/python.exe") // Windows (Scripts subdir)
        ).find { it.toFile().exists() }

    private fun runCommand(executable: String): CommandResult =
        runner.run(
            CommandSpec(executable = executable, args = listOf(ExpandedArg.Literal("--version")) ),
            defaultPolicy
        )
}
