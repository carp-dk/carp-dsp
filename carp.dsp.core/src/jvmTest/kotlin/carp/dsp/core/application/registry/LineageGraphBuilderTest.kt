package carp.dsp.core.application.registry

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import health.workflows.interfaces.api.LineageConformance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// -- Fixtures ------------------------------------------------------------------

private val condaEnv = EnvironmentDescriptor(
    name = "python-env",
    kind = "conda",
    spec = mapOf(
        "pythonVersion" to listOf("3.11"),
        "dependencies" to listOf("pandas", "numpy"),
    ),
)

private val dockerEnv = EnvironmentDescriptor(
    name = "docker-env",
    kind = "docker",
    spec = mapOf("image" to listOf("python:3.11-slim")),
)

private val systemEnv = EnvironmentDescriptor(
    name = "system-env",
    kind = "system",
    spec = emptyMap(),
)

private fun workflowWith(
    steps: List<StepDescriptor>,
    environments: Map<String, EnvironmentDescriptor>,
) = WorkflowDescriptor(
    metadata = WorkflowMetadataDescriptor(name = "Test Workflow"),
    steps = steps,
    environments = environments,
)

// -- Tests --------------------------------------------------------------------

class LineageGraphBuilderTest {

    // -- Node types ------------------------------------------------------------

    @Test
    fun `step nodes have type step`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to systemEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val stepNode = graph.nodes.find { it.id == "s1" }
        assertNotNull(stepNode)
        assertEquals("step", stepNode.type)
        assertEquals("s1", stepNode.label)
    }

    @Test
    fun `environment nodes have type environment`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val envNode = graph.nodes.find { it.id == "env1" }
        assertNotNull(envNode)
        assertEquals("environment", envNode.type)
        assertEquals("python-env", envNode.label)
    }

    @Test
    fun `package nodes have type package`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val pkgNode = graph.nodes.find { it.id == "env1:pandas" }
        assertNotNull(pkgNode)
        assertEquals("package", pkgNode.type)
        assertEquals("pandas", pkgNode.label)
    }

    // -- Node counts -----------------------------------------------------------

    @Test
    fun `one conda step produces correct node count`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        // 1 step + 1 environment + 2 packages (pandas, numpy)
        assertEquals(4, graph.nodes.size)
    }

    @Test
    fun `system environment produces no package nodes`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to systemEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        assertTrue(graph.nodes.none { it.type == "package" })
    }

    // -- Versions --------------------------------------------------------------

    @Test
    fun `conda environment version is pythonVersion`() {
        val wf = workflowWith(
            steps = listOf(StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val envNode = graph.nodes.find { it.id == "env1" }
        assertEquals("3.11", envNode?.version)
    }

    @Test
    fun `docker environment version is image reference`() {
        val wf = workflowWith(
            steps = listOf(StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to dockerEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val envNode = graph.nodes.find { it.id == "env1" }
        assertEquals("python:3.11-slim", envNode?.version)
    }

    // -- Edges -----------------------------------------------------------------

    @Test
    fun `step USES environment edge is present`() {
        val wf = workflowWith(
            steps = listOf(StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val usesEdge = graph.edges.find { it.fromId == "s1" && it.toId == "env1" && it.relation == "USES" }
        assertNotNull(usesEdge)
    }

    @Test
    fun `environment CONTAINS package edges are present`() {
        val wf = workflowWith(
            steps = listOf(StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val containsEdges = graph.edges.filter { it.fromId == "env1" && it.relation == "CONTAINS" }
        assertEquals(2, containsEdges.size)
        assertTrue(containsEdges.any { it.toId == "env1:pandas" })
        assertTrue(containsEdges.any { it.toId == "env1:numpy" })
    }

    // -- Multi-step ------------------------------------------------------------

    @Test
    fun `two steps sharing one environment produce two USES edges`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = PythonTaskDescriptor(name = "s1", entryPoint = ScriptEntryPointDescriptor("a.py"))),
                StepDescriptor(id = "s2", environmentId = "env1", task = PythonTaskDescriptor(name = "s2", entryPoint = ScriptEntryPointDescriptor("b.py"))),
            ),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val usesEdges = graph.edges.filter { it.toId == "env1" && it.relation == "USES" }
        assertEquals(2, usesEdges.size)
    }

    @Test
    fun `two steps with different environments produce independent sub-graphs`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
                StepDescriptor(id = "s2", environmentId = "env2", task = CommandTaskDescriptor(name = "s2", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv, "env2" to dockerEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        assertTrue(graph.nodes.any { it.id == "env1" && it.type == "environment" })
        assertTrue(graph.nodes.any { it.id == "env2" && it.type == "environment" })
        assertTrue(graph.edges.any { it.fromId == "s1" && it.toId == "env1" })
        assertTrue(graph.edges.any { it.fromId == "s2" && it.toId == "env2" })
    }

    @Test
    fun `lineage graph conforms to shared contract`() {
        val wf = workflowWith(
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv),
        )

        val graph = LineageGraphBuilder.build(wf)
        val errors = LineageConformance.validate(graph)
        assertTrue(errors.isEmpty())
    }
}
