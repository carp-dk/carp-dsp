package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an environments view reads from the store.
 *
 * [EnvironmentStore.root] is process-wide, so each test gets its own directory
 * and puts the original back.
 */
class EnvironmentStoreEntriesTest {

    private val initialRoot = EnvironmentStore.root

    @BeforeTest
    fun useTemporaryRoot() {
        EnvironmentStore.root = Files.createTempDirectory("envs")
    }

    @AfterTest
    fun restore() {
        EnvironmentStore.root.toFile().deleteRecursively()
        EnvironmentStore.root = initialRoot
    }

    private val ref = PixiEnvironmentRef(
        id = "env-1",
        name = "carp-task-runtime",
        dependencies = listOf("openjdk=17.*"),
        channels = listOf("conda-forge"),
        pythonVersion = "3.11",
    )

    @Test
    fun `an empty store has no entries`() {
        assertTrue(EnvironmentStore.entries().isEmpty())
    }

    @Test
    fun `a solved environment is listed with what it was built from`() {
        val dir = EnvironmentStore.resolve(ref)!!.directory
        EnvironmentStore.writeManifest(dir, ref)

        val entry = EnvironmentStore.entries().single()

        assertEquals("pixi", entry.kind)
        assertEquals(dir, entry.directory)
        assertEquals("carp-task-runtime", (entry.ref as PixiEnvironmentRef).name)
        assertNotNull(entry.builtAt)
    }

    @Test
    fun `a build that never finished is listed without a definition`() {
        EnvironmentStore.root.resolve("pixi").resolve("half-built-0123456789abcdef").createDirectories()

        val entry = EnvironmentStore.entries().single()

        assertNull(entry.ref)
        assertNull(entry.builtAt)
    }

    @Test
    fun `marking an environment used records when`() {
        val dir = EnvironmentStore.resolve(ref)!!.directory
        EnvironmentStore.writeManifest(dir, ref)
        assertNull(EnvironmentStore.entries().single().lastUsedAt)

        EnvironmentStore.markUsed(dir)

        assertNotNull(EnvironmentStore.entries().single().lastUsedAt)
    }

    @Test
    fun `marking a directory the store does not hold creates nothing`() {
        val missing = EnvironmentStore.root.resolve("conda").resolve("not-here")

        EnvironmentStore.markUsed(missing)

        assertFalse(missing.exists())
        assertTrue(EnvironmentStore.entries().isEmpty())
    }
}
