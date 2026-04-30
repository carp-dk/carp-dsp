package carp.dsp.core.application.translation.snakemake

import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// -- Fixtures ------------------------------------------------------------------

private val condaEnv = EnvironmentDescriptor(
    name = "python-processing",
    kind = "conda",
    spec = mapOf("dependencies" to listOf("pandas")),
)

private val dockerEnv = EnvironmentDescriptor(
    name = "python-slim",
    kind = "docker",
    spec = mapOf("image" to listOf("python:3.11-slim")),
)

private fun step(
    id: String = "process-data",
    environmentId: String = "env",
) = StepDescriptor(
    id = id,
    environmentId = environmentId,
    task = PythonTaskDescriptor(
        name = id,
        entryPoint = ScriptEntryPointDescriptor("scripts/run.py"),
        args = emptyList(),
    ),
    inputs = listOf(DataPortDescriptor(id = "input-csv", source = FileInputSource("data/input.csv"))),
    outputs = listOf(DataPortDescriptor(id = "output-csv", destination = FileOutputDestination("output/result.csv"))),
)

private fun workflowWith(
    vararg steps: StepDescriptor,
    environments: Map<String, EnvironmentDescriptor>,
) = WorkflowDescriptor(
    metadata = WorkflowMetadataDescriptor(name = "Test Workflow"),
    steps = steps.toList(),
    environments = environments,
)

// -- Tests --------------------------------------------------------------------

class DspToSnakemakeExporterTest {

    @Test
    fun `docker environment emits container directive`() {
        val wf = workflowWith(step(environmentId = "docker-env"), environments = mapOf("docker-env" to dockerEnv))
        val snakefile = DspToSnakemakeExporter.export(wf).content

        assertTrue("container: \"docker://python:3.11-slim\"" in snakefile)
    }

    @Test
    fun `docker container directive appears before shell`() {
        val wf = workflowWith(step(environmentId = "docker-env"), environments = mapOf("docker-env" to dockerEnv))
        val snakefile = DspToSnakemakeExporter.export(wf).content

        val containerIdx = snakefile.indexOf("container:")
        val shellIdx = snakefile.indexOf("shell:")
        assertTrue(containerIdx >= 0, "container: directive expected")
        assertTrue(containerIdx < shellIdx, "container: must appear before shell:")
    }

    @Test
    fun `conda environment does not emit container directive`() {
        val wf = workflowWith(step(environmentId = "conda-env"), environments = mapOf("conda-env" to condaEnv))
        val snakefile = DspToSnakemakeExporter.export(wf).content

        assertFalse("container:" in snakefile)
    }

    @Test
    fun `snakefile always contains rule all`() {
        val wf = workflowWith(step(environmentId = "docker-env"), environments = mapOf("docker-env" to dockerEnv))
        val snakefile = DspToSnakemakeExporter.export(wf).content

        assertTrue("rule all:" in snakefile)
    }
}
