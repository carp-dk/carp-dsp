package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.application.environment.REnvironmentDefinition
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class REnvironmentHandlerTest {

    private val handler = REnvironmentHandler()
    private val json = Json

    @Test
    fun `can handle REnvironmentRef`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        assertTrue(handler.canHandle(ref))
    }

    @Test
    fun `cannot handle other environment refs`() {
        val ref = CondaEnvironmentRef(
            id = "conda-001",
            name = "env",
            dependencies = emptyList()
        )

        assertFalse(handler.canHandle(ref))
    }

    @Test
    fun `generates execution command`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val command = handler.generateExecutionCommand(ref, "script.R")

        assertTrue(command.contains("Rscript"))
        assertTrue(command.contains("script.R"))
    }

    @Test
    fun `generates command with --vanilla for renv`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            renvLockFile = "/path/to/renv.lock"
        )

        val command = handler.generateExecutionCommand(ref, "script.R")

        assertTrue(command.contains("--vanilla"))
    }

    @Test
    fun `generates command without --vanilla for system R`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val command = handler.generateExecutionCommand(ref, "script.R")

        assertFalse(command.contains("--vanilla"))
        assertTrue(command.contains("Rscript"))
    }

    @Test
    fun `generates execution command with args`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val command = handler.generateExecutionCommand(ref, "script.R arg1 arg2")

        assertTrue(command.contains("script.R arg1 arg2"))
    }

    @Test
    fun `validate returns false for nonexistent env`() {
        val ref = REnvironmentRef(
            id = "nonexistent",
            rVersion = "4.3.0",
            rPackages = listOf("nonexistent-package-xyz")
        )

        // If R is not installed, this will fail; if it is, it will fail on package
        val result = handler.validate(ref)
        assertFalse(result)
    }

    @Test
    fun `teardown returns boolean for nonexistent env`() {
        val ref = REnvironmentRef(
            id = "nonexistent",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val result = handler.teardown(ref)
        assertTrue(result)
    }

    @Test
    fun `supports dependencies`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2"),
            dependencies = listOf("pandoc", "ghostscript")
        )

        assertEquals(2, ref.dependencies.size)
    }

    @Test
    fun `supports environment variables`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2"),
            environmentVariables = mapOf(
                "R_LIBS" to "/usr/local/lib/R",
                "R_HOME" to "/opt/R/4.3.0"
            )
        )

        assertEquals(2, ref.environmentVariables.size)
    }

    @Test
    fun `supports renv lock file`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            renvLockFile = "/path/to/renv.lock"
        )

        assertTrue(handler.canHandle(ref))
    }

    @Test
    fun `setup fails when renv lock missing`() {
        val ref = REnvironmentRef(
            id = "r-env-missing-lock",
            rVersion = "4.3.0",
            renvLockFile = "/path/does/not/exist/renv.lock"
        )

        val exception = kotlin.runCatching { handler.setup(ref) }.exceptionOrNull()

        assertTrue(exception is EnvironmentProvisioningException)
    }

    @Test
    fun `setup succeeds with stub rscript`() {
        withFakeRscript(failToken = null) {
            val ref = REnvironmentRef(
                id = "r-env-stub",
                rVersion = "4.3.0",
                rPackages = listOf("okpkg")
            )

            val result = handler.setup(ref)

            assertTrue(result)
        }
    }

    @Test
    fun `setup fails when package install fails`() {
        withFakeRscript(failToken = "failpkg") {
            val ref = REnvironmentRef(
                id = "r-env-fail",
                rVersion = "4.3.0",
                rPackages = listOf("failpkg")
            )

            val exception = kotlin.runCatching { handler.setup(ref) }.exceptionOrNull()

            assertTrue(exception is EnvironmentProvisioningException)
        }
    }

    @Test
    fun `supports installation path`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2"),
            installationPath = "/opt/R/4.3.0"
        )

        assertEquals("/opt/R/4.3.0", ref.installationPath)
    }

    private fun withFakeRscript(failToken: String?, block: () -> Unit) {
        val tempDir = createTempDirectory("fake-rscript")
        val originalProp = System.getProperty("carp.rscript")
        try {
            val stub = createStubRscript(tempDir, failToken)
            System.setProperty("carp.rscript", stub.toString())
            block()
        } finally {
            if (originalProp == null) System.clearProperty("carp.rscript") else System.setProperty("carp.rscript", originalProp)
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun createStubRscript(dir: Path, failToken: String?): Path {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val name = if (isWindows) "Rscript.bat" else "Rscript"
        val script = dir.resolve(name)
        val content =
            if (isWindows)
                """@echo off
                    set args=%*
                    echo %args% | findstr /C:"${failToken ?: "__no_fail__"}" >nul
                    if %errorlevel%==0 (
                      exit /b 1
                    )
                    if "%1"=="--version" (
                      echo R version 4.3.0
                      exit /b 0
                    )
                    exit /b 0
                    """.trimIndent()
                                else
                                    """#!/bin/sh
                    args="$@"
                    if [ -n "${failToken ?: ""}" ] && echo "${'$'}args" | grep -q "${failToken ?: ""}"; then
                      exit 1
                    fi
                    if [ "$1" = "--version" ]; then
                      echo "R version 4.3.0"
                      exit 0
                    fi
                    exit 0
                    """.trimIndent()

        script.writeText(content)
        if (!isWindows) {
            script.toFile().setExecutable(true)
        }
        return script
    }


    @Test
    fun `valid definition with packages passes validation`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "4.3.0",
            rPackages = listOf("dplyr", "ggplot2")
        )

        val errors = def.validate()

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `blank version yields error`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "",
            rPackages = listOf("dplyr")
        )

        val errors = def.validate()

        assertTrue(errors.any { it.contains("R version cannot be blank") })
    }

    @Test
    fun `missing packages and lock yields error`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "4.3.0",
            rPackages = emptyList(),
            renvLockFile = null
        )

        val errors = def.validate()

        assertTrue(errors.any { it.contains("Either renvLockFile or rPackages must be specified") })
    }

    @Test
    fun `invalid version format yields error`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "4",
            rPackages = listOf("dplyr")
        )

        val errors = def.validate()

        assertTrue(errors.any { it.contains("Invalid R version format") })
    }

    @Test
    fun `renv lock without packages passes validation`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "4.3.0",
            renvLockFile = "/path/to/renv.lock"
        )

        val errors = def.validate()

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `serializes and deserializes`() {
        val def = REnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "r-env",
            rVersion = "4.3.0",
            rPackages = listOf("dplyr"),
            renvLockFile = null,
            installationPath = "/opt/R/4.3.0",
            dependencies = listOf("pandoc"),
            environmentVariables = mapOf("R_LIBS" to "/usr/local/lib/R")
        )

        val encoded = json.encodeToString(def)
        val decoded = json.decodeFromString<REnvironmentDefinition>(encoded)

        assertEquals(def, decoded)
    }
}
