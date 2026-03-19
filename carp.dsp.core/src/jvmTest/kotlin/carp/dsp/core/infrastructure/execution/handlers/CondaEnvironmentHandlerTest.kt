package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.testing.MockCommandRunner
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import java.io.IOException
import kotlin.test.*

class CondaEnvironmentHandlerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ref(
        name: String = "test-env",
        pythonVersion: String = "3.11",
        dependencies: List<String> = emptyList(),
        channels: List<String> = emptyList(),
    ) = CondaEnvironmentRef(
        id = "test-001", name = name, pythonVersion = pythonVersion,
        dependencies = dependencies, channels = channels
    )

    private fun MockCommandRunner.stubFullSuccess(name: String = "test-env") {
        on("conda --version")
        on("conda create")
        on("conda env list", stdout = "$name  /opt/conda/envs/$name")
        on("conda run -n $name python --version", stdout = "Python 3.11.0")
    }

    // ── canHandle ─────────────────────────────────────────────────────────────

    @Test fun `can handle CondaEnvironmentRef`() =
        assertTrue(CondaEnvironmentHandler().canHandle(ref()))

    @Test fun `cannot handle PixiEnvironmentRef`() =
        assertFalse(
            CondaEnvironmentHandler().canHandle(
            PixiEnvironmentRef(id = "p", name = "p-env", dependencies = emptyList())
        )
        )

    // ── generateExecutionCommand ──────────────────────────────────────────────
    // Pure string logic — no process execution, no mock needed.

    @Test fun `generates correct execution command`() =
        assertEquals(
            "conda run -n my-env python script.py arg1",
            CondaEnvironmentHandler().generateExecutionCommand(ref(name = "my-env"), "python script.py arg1")
        )

    @Test fun `generates command with hyphenated env name`() {
        val cmd = CondaEnvironmentHandler().generateExecutionCommand(
            ref(name = "eeg-analysis-v2"), "python analyze.py"
        )
        assertTrue(cmd.startsWith("conda run -n eeg-analysis-v2"))
    }

    @Test fun `generates module invocation command`() =
        assertEquals(
            "conda run -n ml-env python -m pkg.mod --flag=v",
            CondaEnvironmentHandler().generateExecutionCommand(ref(name = "ml-env"), "python -m pkg.mod --flag=v")
        )

    // ── teardown ──────────────────────────────────────────────────────────────

    @Test fun `teardown returns true when conda exits 0`() {
        val mock = MockCommandRunner().apply { on("conda env remove") }
        assertTrue(CondaEnvironmentHandler(mock).teardown(ref(name = "my-env")))
    }

    @Test fun `teardown returns true for nonexistent environment (conda silent exit 0)`() {
        // conda env remove exits 0 silently when the env doesn't exist.
        // Teardown's postcondition is "env does not exist" — already satisfied either way.
        val mock = MockCommandRunner().apply { on("conda env remove") }
        assertTrue(CondaEnvironmentHandler(mock).teardown(ref(name = "ghost-env")))
    }

    @Test fun `teardown returns false when conda exits non-zero`() {
        val mock = MockCommandRunner().apply { on("conda env remove", exitCode = 1) }
        assertFalse(CondaEnvironmentHandler(mock).teardown(ref(name = "my-env")))
    }

    @Test fun `teardown returns false when runner throws`() {
        val mock = MockCommandRunner().apply {
            onThrow("conda env remove", IOException("conda binary not found"))
        }
        assertFalse(CondaEnvironmentHandler(mock).teardown(ref(name = "my-env")))
    }

    @Test fun `teardown command includes -y flag to suppress interactive prompt`() {
        val mock = MockCommandRunner().apply { on("conda env remove") }
        CondaEnvironmentHandler(mock).teardown(ref(name = "my-env"))
        val call = mock.capturedCommands.single { it.contains("remove") }
        assertTrue(call.contains("-y"), "remove command must include -y: $call")
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @Test fun `setup throws EnvironmentSetupException when conda is not installed`() {
        val mock = MockCommandRunner().apply { on("conda --version", exitCode = 1) }
        val ex = assertFailsWith<EnvironmentSetupException> {
            CondaEnvironmentHandler(mock).setup(ref())
        }
        assertTrue(ex.message!!.contains("Conda not found"))
    }

    @Test fun `setup throws EnvironmentSetupException when conda create fails`() {
        val mock = MockCommandRunner().apply {
            on("conda --version")
            on("conda create", exitCode = 1, stderr = "SolverError: package not found")
        }
        val ex = assertFailsWith<EnvironmentSetupException> {
            CondaEnvironmentHandler(mock).setup(ref())
        }
        assertTrue(ex.message!!.contains("Failed to create conda environment"))
    }

    @Test fun `setup throws EnvironmentSetupException when validation fails after create`() {
        val mock = MockCommandRunner().apply {
            on("conda --version")
            on("conda create")
            on("conda env list", stdout = "base  *  /opt/conda") // env absent from list
        }
        val ex = assertFailsWith<EnvironmentSetupException> {
            CondaEnvironmentHandler(mock).setup(ref(name = "my-env"))
        }
        assertTrue(ex.message!!.contains("validation failed"))
    }

    @Test fun `setup returns true on full success with no dependencies`() {
        val mock = MockCommandRunner().apply { stubFullSuccess("my-env") }
        assertTrue(CondaEnvironmentHandler(mock).setup(ref(name = "my-env")))
    }

    @Test fun `setup passes conda packages and channels to create command`() {
        val mock = MockCommandRunner().apply {
            stubFullSuccess("my-env")
            on("conda run -n my-env python -c import numpy")
            on("conda run -n my-env python -c import scipy")
        }
        CondaEnvironmentHandler(mock).setup(
            ref(
            name = "my-env",
            dependencies = listOf("numpy", "scipy"),
            channels = listOf("conda-forge", "bioconda")
        )
        )
        val createCmd = mock.capturedCommands.single { it.contains("create") }
        assertTrue(createCmd.contains("numpy"))
        assertTrue(createCmd.contains("scipy"))
        assertTrue(createCmd.contains("-c conda-forge"))
        assertTrue(createCmd.contains("-c bioconda"))
    }

    @Test fun `setup installs pip packages via separate conda run pip install`() {
        val mock = MockCommandRunner().apply {
            on("conda --version")
            on("conda create")
            on("conda run -n my-env pip install")
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import pip:black")
            on("conda run -n my-env python -c import pip:ruff")
        }
        assertTrue(
            CondaEnvironmentHandler(mock).setup(
                ref(
            name = "my-env", dependencies = listOf("pip:black", "pip:ruff")
        )
            )
        )
        val pipCmd = mock.capturedCommands.single { it.contains("pip install") }
        assertTrue(pipCmd.contains("black"))
        assertTrue(pipCmd.contains("ruff"))
    }

    @Test fun `setup throws when pip install step fails`() {
        val mock = MockCommandRunner().apply {
            on("conda --version")
            on("conda create")
            on("conda run -n my-env pip install", exitCode = 1, stderr = "pip error")
        }
        assertFailsWith<EnvironmentSetupException> {
            CondaEnvironmentHandler(mock).setup(ref(name = "my-env", dependencies = listOf("pip:black")))
        }
    }

    @Test fun `setup splits mixed conda and pip dependencies correctly`() {
        val mock = MockCommandRunner().apply {
            on("conda --version")
            on("conda create")
            on("conda run -n my-env pip install")
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import numpy")
            on("conda run -n my-env python -c import pip:black")
        }
        assertTrue(
            CondaEnvironmentHandler(mock).setup(
                ref(
            name = "my-env", dependencies = listOf("numpy", "pip:black")
        )
            )
        )
        val createCmd = mock.capturedCommands.single { it.contains("create") }
        assertTrue(createCmd.contains("numpy"), "conda package must appear in create cmd")
        assertFalse(createCmd.contains("black"), "pip package must NOT appear in create cmd")
    }

    @Test fun `setup throws when verifyCondaInstalled runner throws`() {
        val mock = MockCommandRunner().apply {
            onThrow("conda --version", IOException("conda not on PATH"))
        }
        val ex = assertFailsWith<EnvironmentSetupException> {
            CondaEnvironmentHandler(mock).setup(ref())
        }
        assertTrue(ex.message!!.contains("Conda not found"))
    }

    // ── validate ──────────────────────────────────────────────────────────────

    @Test fun `validate returns false when conda env list exits non-zero`() {
        val mock = MockCommandRunner().apply { on("conda env list", exitCode = 1) }
        assertFalse(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate returns false when env is absent from conda env list`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "base  *  /opt/conda\nother  /opt/conda/envs/other")
        }
        assertFalse(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate returns false when python version check fails`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", exitCode = 1)
        }
        assertFalse(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate returns false when a dependency import fails`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import numpy")
            on("conda run -n my-env python -c import scipy", exitCode = 1)
        }
        assertFalse(
            CondaEnvironmentHandler(mock).validate(
                ref(
            name = "my-env", dependencies = listOf("numpy", "scipy")
        )
            )
        )
    }

    @Test fun `validate returns true when env exists and all deps import`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import numpy")
            on("conda run -n my-env python -c import scipy")
        }
        assertTrue(
            CondaEnvironmentHandler(mock).validate(
                ref(
            name = "my-env", dependencies = listOf("numpy", "scipy")
        )
            )
        )
    }

    @Test fun `validate returns false when runner throws`() {
        val mock = MockCommandRunner().apply {
            onThrow("conda env list", IOException("conda not found"))
        }
        assertFalse(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    // condaEnvironmentExists — all four line-matching patterns

    @Test fun `validate recognises env by name-space prefix`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
        }
        assertTrue(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate recognises active env with asterisk prefix`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "* my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
        }
        assertTrue(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate recognises env by unix path segment`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "/opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
        }
        assertTrue(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    @Test fun `validate recognises env by windows path segment`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "C:\\Users\\user\\.conda\\envs\\my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
        }
        assertTrue(CondaEnvironmentHandler(mock).validate(ref(name = "my-env")))
    }

    // Dependency name extraction edge cases

    @Test fun `validate strips version specifier before import check`() {
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import numpy")
        }
        assertTrue(
            CondaEnvironmentHandler(mock).validate(
                ref(
            name = "my-env", dependencies = listOf("numpy=1.24.0")
        )
            )
        )
    }

    @Test fun `validate uses part before slash as module name`() {
        // "conda-forge/scipy" → split("/")[0] = "conda-forge" is the module name passed to import
        val mock = MockCommandRunner().apply {
            on("conda env list", stdout = "my-env  /opt/conda/envs/my-env")
            on("conda run -n my-env python --version", stdout = "Python 3.11.0")
            on("conda run -n my-env python -c import conda-forge")
        }
        assertTrue(
            CondaEnvironmentHandler(mock).validate(
                ref(
            name = "my-env", dependencies = listOf("conda-forge/scipy")
        )
            )
        )
    }
}
