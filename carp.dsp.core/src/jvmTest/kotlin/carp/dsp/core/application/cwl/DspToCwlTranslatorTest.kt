package carp.dsp.core.application.cwl

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import carp.dsp.core.application.translation.cwl.DspToCwlExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// -- Fixtures ------------------------------------------------------------------

private val condaEnv = EnvironmentDescriptor(
    name = "python-processing",
    kind = "conda",
    spec = mapOf(
        "dependencies" to listOf("pandas", "numpy"),
        "pythonVersion" to listOf("3.11"),
    ),
)

private val condaEnvWithVars = condaEnv.copy(
    spec = condaEnv.spec + mapOf("env" to listOf("MY_VAR=hello", "OTHER=world")),
)

private val rEnv = EnvironmentDescriptor(
    name = "r-processing",
    kind = "r",
    spec = mapOf("rVersion" to listOf("4.5.3")),
)

private val systemEnv = EnvironmentDescriptor(
    name = "system",
    kind = "system",
    spec = emptyMap(),
)

private val dockerEnv = EnvironmentDescriptor(
    name = "python-slim",
    kind = "docker",
    spec = mapOf("image" to listOf("python:3.11-slim")),
)

private fun pythonStep(
    id: String = "process-data",
    environmentId: String = "conda-env",
    scriptPath: String = "scripts/process_data.py",
    args: List<String> = listOf("input.0", "output.0"),
    inputs: List<DataPortDescriptor> = listOf(
        DataPortDescriptor(id = "input-csv", source = FileInputSource("data/input.csv")),
    ),
    outputs: List<DataPortDescriptor> = listOf(
        DataPortDescriptor(id = "output-csv", destination = FileOutputDestination("data/output.csv")),
    ),
) = StepDescriptor(
    id = id,
    environmentId = environmentId,
    task = PythonTaskDescriptor(
        name = id,
        entryPoint = ScriptEntryPointDescriptor(scriptPath),
        args = args,
    ),
    inputs = inputs,
    outputs = outputs,
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

class DspToCwlExporterTest {

    // -- Python / Conda --------------------------------------------------------

    @Test
    fun `python script step has correct baseCommand`() {
        val wf = workflowWith(
            pythonStep(),
            environments = mapOf("conda-env" to condaEnv),
        )
        val assets = DspToCwlExporter.export(wf)

        assertEquals(1, assets.size)
        val cwl = assets.first().content
        assertTrue("- python" in cwl, "Expected 'python' in baseCommand")
        assertTrue("- scripts/process_data.py" in cwl)
    }

    @Test
    fun `conda environment produces SoftwareRequirement`() {
        val wf = workflowWith(pythonStep(), environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("SoftwareRequirement" in cwl)
        assertTrue("pandas" in cwl)
        assertTrue("numpy" in cwl)
    }

    @Test
    fun `environment variables produce EnvVarRequirement`() {
        val wf = workflowWith(pythonStep(), environments = mapOf("conda-env" to condaEnvWithVars))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("EnvVarRequirement" in cwl)
        assertTrue("MY_VAR" in cwl)
        assertTrue("hello" in cwl)
    }

    @Test
    fun `step arguments appear in CWL arguments block`() {
        val wf = workflowWith(
            pythonStep(args = listOf("--verbose", "--output", "out.csv")),
            environments = mapOf("conda-env" to condaEnv)
        )
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("arguments:" in cwl)
        assertTrue("--verbose" in cwl)
    }

    // -- Python module entry point ---------------------------------------------

    @Test
    fun `python module entry point uses -m flag`() {
        val step = StepDescriptor(
            id = "run-module",
            environmentId = "conda-env",
            task = PythonTaskDescriptor(
                name = "run-module",
                entryPoint = ModuleEntryPointDescriptor("mypackage.main"),
            ),
        )
        val wf = workflowWith(step, environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("- python" in cwl)
        assertTrue("- -m" in cwl)
        assertTrue("- mypackage.main" in cwl)
    }

    // -- R --------------------------------------------------------------------

    @Test
    fun `R step uses Rscript baseCommand`() {
        val step = StepDescriptor(
            id = "r-step",
            environmentId = "r-env",
            task = RTaskDescriptor(
                name = "r-step",
                entryPoint = RScriptEntryPointDescriptor("scripts/process_data.R"),
                args = listOf("input.csv"),
            ),
            inputs = listOf(DataPortDescriptor(id = "input-csv", source = FileInputSource("data/input.csv"))),
            outputs = listOf(DataPortDescriptor(id = "output-csv", destination = FileOutputDestination("data/output.csv"))),
        )
        val wf = workflowWith(step, environments = mapOf("r-env" to rEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("- Rscript" in cwl)
        assertTrue("- scripts/process_data.R" in cwl)
        assertFalse("SoftwareRequirement" in cwl, "R env should not produce SoftwareRequirement")
    }

    // -- Command task ----------------------------------------------------------

    @Test
    fun `command task maps executable to baseCommand`() {
        val step = StepDescriptor(
            id = "copy-step",
            environmentId = "system-env",
            task = CommandTaskDescriptor(name = "copy-step", executable = "cp", args = listOf("src.csv", "dst.csv")),
        )
        val wf = workflowWith(step, environments = mapOf("system-env" to systemEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("- cp" in cwl)
        assertFalse("SoftwareRequirement" in cwl)
    }

    // -- InProcess -------------------------------------------------------------

    @Test
    fun `InProcess step is skipped`() {
        val step = StepDescriptor(
            id = "in-process-step",
            environmentId = "system-env",
            task = InProcessTaskDescriptor(name = "in-process-step", operationId = "my.operation"),
        )
        val wf = workflowWith(step, environments = mapOf("system-env" to systemEnv))
        val assets = DspToCwlExporter.export(wf)

        assertTrue(assets.isEmpty(), "InProcess steps should be skipped")
    }

    // -- Inputs / outputs ------------------------------------------------------

    @Test
    fun `file inputs are typed as File in CWL`() {
        val wf = workflowWith(pythonStep(), environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("type: File" in cwl)
        assertTrue("inputBinding:" in cwl)
    }

    @Test
    fun `file outputs include glob`() {
        val wf = workflowWith(pythonStep(), environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("outputBinding:" in cwl)
        assertTrue("glob:" in cwl)
        assertTrue("data/output.csv" in cwl)
    }

    // -- Multi-step workflow ---------------------------------------------------

    @Test
    fun `multi-step workflow produces one asset per translatable step`() {
        val inProcess = StepDescriptor(
            id = "in-process",
            environmentId = "system-env",
            task = InProcessTaskDescriptor(name = "in-process", operationId = "op"),
        )
        val wf = workflowWith(
            pythonStep(id = "step-a"),
            pythonStep(id = "step-b", scriptPath = "scripts/other.py"),
            inProcess,
            environments = mapOf("conda-env" to condaEnv, "system-env" to systemEnv),
        )
        val assets = DspToCwlExporter.export(wf)

        assertEquals(2, assets.size, "Expected 2 assets: two Python steps; InProcess skipped")
        assertEquals("step-a", assets[0].stepId)
        assertEquals("step-b", assets[1].stepId)
    }

    // -- CWL structure ---------------------------------------------------------

    @Test
    fun `output declares cwlVersion and class`() {
        val wf = workflowWith(pythonStep(), environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("cwlVersion: v1.2" in cwl)
        assertTrue("class: CommandLineTool" in cwl)
        assertEquals("v1.2", DspToCwlExporter.export(wf).first().toolVersion)
    }

    @Test
    fun `system environment produces no SoftwareRequirement`() {
        val step = pythonStep(environmentId = "system-env")
        val wf = workflowWith(step, environments = mapOf("system-env" to systemEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertFalse("SoftwareRequirement" in cwl)
    }

    // -- Docker ----------------------------------------------------------------

    @Test
    fun `docker environment emits DockerRequirement with correct image`() {
        val step = pythonStep(environmentId = "docker-env")
        val wf = workflowWith(step, environments = mapOf("docker-env" to dockerEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("DockerRequirement" in cwl)
        assertTrue("dockerPull: \"python:3.11-slim\"" in cwl)
    }

    @Test
    fun `docker environment does not emit SoftwareRequirement`() {
        val step = pythonStep(environmentId = "docker-env")
        val wf = workflowWith(step, environments = mapOf("docker-env" to dockerEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertFalse("SoftwareRequirement" in cwl)
    }

    @Test
    fun `docker DockerRequirement is in requirements not hints`() {
        val step = pythonStep(environmentId = "docker-env")
        val wf = workflowWith(step, environments = mapOf("docker-env" to dockerEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        val requirementsIdx = cwl.indexOf("requirements:")
        val hintsIdx = cwl.indexOf("hints:")
        val dockerIdx = cwl.indexOf("DockerRequirement")

        assertTrue(requirementsIdx >= 0, "requirements: block expected")
        assertTrue(dockerIdx > requirementsIdx, "DockerRequirement must appear after requirements:")
        assertTrue(hintsIdx !in 0..dockerIdx, "DockerRequirement must not be under hints:")
    }

    @Test
    fun `conda environment still produces SoftwareRequirement when docker env present on other steps`() {
        val condaStep = pythonStep(id = "step-a", environmentId = "conda-env")
        val wf = workflowWith(condaStep, environments = mapOf("conda-env" to condaEnv))
        val cwl = DspToCwlExporter.export(wf).first().content

        assertTrue("SoftwareRequirement" in cwl)
        assertFalse("DockerRequirement" in cwl)
    }
}
