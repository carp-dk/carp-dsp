package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Handles R environment setup, execution, and teardown.
 *
 * Supports:
 * - renv-managed environments (preferred)
 * - System R with direct package installation
 * - R with a custom installation path via [rscriptCommand]
 *
 * @param runner The core [CommandRunner] used for all process invocations.
 */
class REnvironmentHandler(
    private val runner: CommandRunner = JvmCommandRunner()
) : EnvironmentHandler {

    private val defaultPolicy = CommandPolicy()

    override fun canHandle(environmentRef: EnvironmentRef): Boolean =
        environmentRef is REnvironmentRef

    override fun setup(environmentRef: EnvironmentRef): Boolean {
        val r = environmentRef as REnvironmentRef

        return try {
            check(verifyRInstalled(r)) { "R ${r.rVersion} not found. Install R and add to PATH." }

            val envDir = getREnvironmentDirectory(r.id)
            envDir.createDirectories()

            if (r.renvLockFile != null) {
                check(setupRenv(envDir, r)) { "Failed to setup renv environment" }
            } else {
                check(installRPackages(r)) { "Failed to install R packages" }
            }

            check(validate(r)) { "Environment created but validation failed" }

            true
        } catch (e: IllegalStateException) {
            throw EnvironmentSetupException(
                message = "Failed to provision R environment '${r.name}': ${e.message}",
                envId = r.id,
                cause = e
            )
        } catch (e: IOException) {
            throw EnvironmentSetupException(
                message = "Failed to provision R environment '${r.name}': ${e.message}",
                envId = r.id,
                cause = e
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EnvironmentSetupException(
                message = "R setup interrupted for '${r.name}': ${e.message}",
                envId = r.id,
                cause = e
            )
        }
    }

    override fun generateExecutionCommand(environmentRef: EnvironmentRef, command: String): String
    {
        val r = environmentRef as REnvironmentRef
        return if (r.renvLockFile != null) {
            val rscript = rscriptCommand()
            "$rscript --vanilla ${command.removePrefix("Rscript").trimStart()}"
        } else command
    }

    override fun teardown(environmentRef: EnvironmentRef): Boolean
    {
        val r = environmentRef as REnvironmentRef

        return try {
            val envDir = getREnvironmentDirectory(r.id)
            if (envDir.toFile().exists()) {
                envDir.toFile().deleteRecursively()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun validate(environmentRef: EnvironmentRef): Boolean {
        val r = environmentRef as REnvironmentRef

        return try {
            val versionCheck = runCommand(rscriptCommand(), "--version")
            if (versionCheck.exitCode != 0) return false

            val rVersionOutput = "${versionCheck.stdout}\n${versionCheck.stderr}".trim()
            if (!isRVersionCompatible(rVersionOutput, r.rVersion)) return false

            for (pkg in r.rPackages) {
                val pkgName = pkg.split("/")[0].split("@")[0].split("=")[0]
                val pkgCheck = runCommand(
                    rscriptCommand(),
                    "-e",
                    "if (!require('$pkgName', quietly=TRUE)) quit(status=1)"
                )
                if (pkgCheck.exitCode != 0) return false
            }

            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun verifyRInstalled(r: REnvironmentRef): Boolean = try {
        val result = runCommand(rscriptCommand(), "--version")
        result.exitCode == 0 &&
                isRVersionCompatible("${result.stdout}\n${result.stderr}", r.rVersion)
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isRVersionCompatible(versionOutput: String, requestedVersion: String): Boolean {
        val normalizedRequested = requestedVersion.trim()
        if (normalizedRequested.isEmpty()) return false

        val installedVersion =
            Regex("""R version\s+(\d+(?:\.\d+){0,2})""")
                .find(versionOutput)?.groupValues?.get(1)
                ?: Regex("""(\d+(?:\.\d+){0,2})""")
                    .find(versionOutput)?.groupValues?.get(1)
                ?: return false

        val requestedParts = normalizedRequested.split(".")
        val installedParts = installedVersion.split(".")
        val segmentsToMatch = minOf(2, requestedParts.size, installedParts.size)

        return (0 until segmentsToMatch).all { idx -> requestedParts[idx] == installedParts[idx] }
    }

    private fun getREnvironmentDirectory(envId: String): Path =
        Path.of(System.getProperty("user.home"), ".carp-dsp", "envs", "r", envId)

    private fun setupRenv(envDir: Path, r: REnvironmentRef): Boolean = try {
        val lockFilePath = Path.of(r.renvLockFile!!)
        check(lockFilePath.exists()) { "renv.lock file not found: ${r.renvLockFile}" }

        val targetLock = envDir.resolve("renv.lock")
        lockFilePath.toFile().copyTo(targetLock.toFile(), overwrite = true)

        val rprofile = envDir.resolve(".Rprofile")
        rprofile.writeText(
            """
            if (file.exists("renv/activate.R")) {
              source("renv/activate.R")
            } else {
              message("renv not activated")
            }
        """.trimIndent()
        )

        // renv::restore() must run from the project directory.
        // See PixiEnvironmentHandler for notes on working-dir handling.
        val spec = CommandSpec(
            executable = rscriptCommand(),
            args = listOf(ExpandedArg.Literal("-e"), ExpandedArg.Literal("renv::restore()"))
        )
        val result = when (val r2 = runner) {
            is JvmCommandRunner -> r2.run(spec, defaultPolicy, envDir)
            else -> runner.run(spec, defaultPolicy)
        }
        result.exitCode == 0
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun installRPackages(r: REnvironmentRef): Boolean = try {
        for (pkg in r.rPackages) {
            val result = runCommand(rscriptCommand(), "-e", "install.packages('$pkg')")
            check(result.exitCode == 0) { "Failed to install package: $pkg" }
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    /**
     * Builds a [CommandSpec] with all-[ExpandedArg.Literal] arguments and runs it
     * via [runner] with [defaultPolicy] and no working-directory override.
     */
    private fun runCommand(executable: String, vararg args: String): CommandResult =
        runner.run(
            CommandSpec(executable = executable, args = args.map { ExpandedArg.Literal(it) }),
            defaultPolicy
        )

    /**
     * Resolves the Rscript binary path.
     *
     * Check order:
     * 1. `carp.rscript` system property
     * 2. `CARP_RSCRIPT` env var
     * 3. `Rscript` on PATH
     * 4. Windows: search `%PROGRAMFILES%\R\` for latest installed version
     */
    private fun rscriptCommand(): String {
        System.getProperty("carp.rscript")?.let { return it }
        System.getenv("CARP_RSCRIPT")?.let { return it }

        // Check if Rscript is already on PATH
        try {
            val result = Runtime.getRuntime().exec(arrayOf("Rscript", "--version"))
            result.waitFor()
            if (result.exitValue() == 0) return "Rscript"
        } catch (_: Exception) { }

        // Windows fallback: scan C:\Program Files\R\ for latest version
        if (System.getProperty("os.name", "").lowercase().contains("windows")) {
            val programFiles = System.getenv("PROGRAMFILES") ?: "C:\\Program Files"
            val rRoot = File(programFiles, "R")
            if (rRoot.isDirectory) {
                val rscript = rRoot.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedDescending()
                    ?.map { File(it, "bin\\Rscript.exe") }
                    ?.firstOrNull { it.exists() }
                if (rscript != null) return rscript.absolutePath
            }
        }

        return "Rscript"
    }
}
