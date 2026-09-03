package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentStoreTest {

    private val initialReuse = EnvironmentStore.reuse

    @AfterTest
    fun restore() {
        EnvironmentStore.reuse = initialReuse
    }

    private fun pixi(
        id: String = "env-1",
        name: String = "python-pixi",
        python: String = "3.12",
        deps: List<String> = listOf("pypi:mobgap"),
        channels: List<String> = listOf("conda-forge"),
    ) = PixiEnvironmentRef(
        id = id,
        name = name,
        dependencies = deps,
        channels = channels,
        pythonVersion = python,
    )

    private fun r(
        id: String = "env-1",
        name: String = "r-env",
        version: String = "4.3.0",
        packages: List<String> = listOf("ggplot2"),
        lock: String? = null,
    ) = REnvironmentRef(
        id = id,
        name = name,
        rVersion = version,
        rPackages = packages,
        renvLockFile = lock,
    )

    private fun directoryOf(ref: dk.cachet.carp.analytics.application.plan.EnvironmentRef) =
        EnvironmentStore.resolve(ref)!!.directory

    // ── Identity ──────────────────────────────────────────────────────────────

    @Test
    fun `the same spec resolves to the same directory, whatever the id or the order`() {
        val first = directoryOf(pixi(id = "run-1", deps = listOf("pypi:mobgap", "pypi:seaborn")))
        val second = directoryOf(pixi(id = "run-2", deps = listOf("pypi:seaborn", "pypi:mobgap")))

        // The id is derived from a random workflow namespace; keying on it meant
        // a fresh environment and a full solve on every run.
        assertEquals(first, second)
    }

    @Test
    fun `a changed dependency or interpreter earns its own directory`() {
        val base = directoryOf(pixi(deps = listOf("pypi:mobgap", "pypi:seaborn")))

        assertNotEquals(base, directoryOf(pixi(deps = listOf("pypi:mobgap"))))
        assertNotEquals(base, directoryOf(pixi(deps = listOf("pypi:mobgap", "pypi:seaborn"), python = "3.11")))
    }

    @Test
    fun `the directory is named for the environment, not for its id`() {
        val directory = directoryOf(pixi(id = "some-uuid", name = "python-pixi")).fileName.toString()

        assertTrue(directory.startsWith("python-pixi-"), directory)
        assertTrue(!directory.contains("some-uuid"), directory)
    }

    @Test
    fun `an environment with nothing to provision resolves to nothing`() {
        // A system environment is whatever the machine already has, so there is
        // no directory to share and nothing to match on.
        assertNull(EnvironmentStore.resolve(SystemEnvironmentRef(id = "sys-1")))
    }

    // ── R, which keys on different fields ─────────────────────────────────────

    @Test
    fun `R keys on its own fields, and on the lock file when there is one`() {
        val base = directoryOf(r(id = "run-1", packages = listOf("ggplot2", "dplyr")))

        assertEquals(base, directoryOf(r(id = "run-2", packages = listOf("dplyr", "ggplot2"))))
        assertNotEquals(base, directoryOf(r(packages = listOf("ggplot2"))))
        assertNotEquals(base, directoryOf(r(packages = listOf("ggplot2", "dplyr"), version = "4.2.0")))

        // A lock file pins the whole closure, so two otherwise identical
        // environments with different locks are different environments.
        assertNotEquals(
            directoryOf(r(lock = "a/renv.lock")),
            directoryOf(r(lock = "b/renv.lock")),
        )
        assertNotEquals(base, directoryOf(r(packages = listOf("ggplot2", "dplyr"), lock = "a/renv.lock")))
    }

    @Test
    fun `R and pixi environments never share a directory`() {
        assertNotEquals(
            directoryOf(pixi(name = "shared")).parent,
            directoryOf(r(name = "shared")).parent,
        )
    }

    // ── Reuse ─────────────────────────────────────────────────────────────────

    @Test
    fun `an unprovisioned environment is built, under either policy`() {
        EnvironmentStore.reuse = ReusePolicy.EXACT
        assertEquals(EnvironmentResolution.BUILT, EnvironmentStore.resolve(pixi(deps = listOf("pypi:nothing-here")))!!.match)

        EnvironmentStore.reuse = ReusePolicy.ALLOW_SUPERSET
        assertEquals(EnvironmentResolution.BUILT, EnvironmentStore.resolve(pixi(deps = listOf("pypi:nothing-here")))!!.match)
    }
}
