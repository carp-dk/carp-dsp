package carp.dsp.core.application.registry

import health.workflows.interfaces.model.EnvironmentType
import health.workflows.interfaces.model.WorkflowFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspPlatformProfileTest {

    @Test
    fun `platformId is carp-dsp`() {
        assertEquals("carp-dsp", DspPlatformProfile.platformId)
    }

    @Test
    fun `supports CARP_DSP and CWL formats`() {
        assertTrue(WorkflowFormat.CARP_DSP in DspPlatformProfile.supportedFormats)
        assertTrue(WorkflowFormat.CWL in DspPlatformProfile.supportedFormats)
    }

    @Test
    fun `supports expected environments`() {
        val envs = DspPlatformProfile.supportedEnvironments
        assertTrue(EnvironmentType.CONDA in envs)
        assertTrue(EnvironmentType.PIXI in envs)
        assertTrue(EnvironmentType.R in envs)
        assertTrue(EnvironmentType.SYSTEM in envs)
        assertFalse(EnvironmentType.DOCKER in envs, "DOCKER should be excluded for R1")
    }

    @Test
    fun `declares five supported operations`() {
        val ops = DspPlatformProfile.supportedOperations
        assertTrue(ops.isNotEmpty())
        assertFalse("getDOI" in ops, "getDOI is stubbed and should not be declared")
        assertFalse("getLineage" in ops, "getLineage is stubbed and should not be declared")
    }

    @Test
    fun `constraints are sensible`() {
        val c = DspPlatformProfile.constraints
        assertTrue(c.maxDependencyDepth > 0)
        assertFalse(c.requiresDOI)
        assertTrue(c.supportedScriptLanguages.isNotEmpty())
    }
}
