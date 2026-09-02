package carp.dsp.core.application.registry

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.ProtocolInputSource
import carp.dsp.core.application.authoring.descriptor.ProtocolRefDescriptor
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
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
            steps = listOf(DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val envNode = graph.nodes.find { it.id == "env1" }
        assertEquals("3.11", envNode?.version)
    }

    @Test
    fun `docker environment version is image reference`() {
        val wf = workflowWith(
            steps = listOf(DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
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
            steps = listOf(DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
            environments = mapOf("env1" to condaEnv),
        )
        val graph = LineageGraphBuilder.build(wf)
        val usesEdge = graph.edges.find { it.fromId == "s1" && it.toId == "env1" && it.relation == "USES" }
        assertNotNull(usesEdge)
    }

    @Test
    fun `environment CONTAINS package edges are present`() {
        val wf = workflowWith(
            steps = listOf(DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo"))),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = PythonTaskDescriptor(name = "s1", entryPoint = ScriptEntryPointDescriptor("a.py"))),
                DefinedStepDescriptor(id = "s2", environmentId = "env1", task = PythonTaskDescriptor(name = "s2", entryPoint = ScriptEntryPointDescriptor("b.py"))),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
                DefinedStepDescriptor(id = "s2", environmentId = "env2", task = CommandTaskDescriptor(name = "s2", executable = "echo")),
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
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to condaEnv),
        )

        val graph = LineageGraphBuilder.build(wf)
        val errors = LineageConformance.validate(graph)
        assertTrue(errors.isEmpty())
    }

    // -- Protocol nodes and CONSUMES_FROM edges --------------------------------

    private val protocolA = "aabbccdd-0000-4000-8000-000000000001"
    private val protocolB = "aabbccdd-0000-4000-8000-000000000002"
    private val heartRate = "dk.cachet.carp.heartrate"
    private val stepCount = "dk.cachet.carp.stepcount"

    private fun protocolInput(
        portId: String,
        dataType: String,
        protocolId: String = protocolA,
        version: Int? = null,
        name: String? = null,
    ) = DataPortDescriptor(
        id = portId,
        source = ProtocolInputSource(
            protocol = ProtocolRefDescriptor(id = protocolId, version = version, name = name),
            dataType = dataType,
        ),
    )

    private fun stepConsuming(id: String, vararg inputs: DataPortDescriptor) = DefinedStepDescriptor(
        id = id,
        environmentId = "env1",
        task = CommandTaskDescriptor(name = id, executable = "echo"),
        inputs = inputs.toList(),
    )

    @Test
    fun `protocol-bound input produces a protocol node labelled by name`() {
        val wf = workflowWith(
            steps = listOf(stepConsuming("s1", protocolInput("hr", heartRate, version = 2, name = "HR study"))),
            environments = mapOf("env1" to systemEnv),
        )

        val node = LineageGraphBuilder.build(wf).nodes.single { it.type == "protocol" }
        assertEquals(protocolA, node.id)
        assertEquals("2", node.version)
        assertEquals("HR study", node.label)
    }

    @Test
    fun `protocol node falls back to id when no name is declared`() {
        val wf = workflowWith(
            steps = listOf(stepConsuming("s1", protocolInput("hr", heartRate))),
            environments = mapOf("env1" to systemEnv),
        )

        val node = LineageGraphBuilder.build(wf).nodes.single { it.type == "protocol" }
        assertEquals(protocolA, node.label)
        assertEquals("", node.version, "An unpinned reference has no version")
    }

    @Test
    fun `step consuming a protocol gets a CONSUMES_FROM edge`() {
        val wf = workflowWith(
            steps = listOf(stepConsuming("s1", protocolInput("hr", heartRate))),
            environments = mapOf("env1" to systemEnv),
        )

        val edge = LineageGraphBuilder.build(wf).edges.single { it.relation == "CONSUMES_FROM" }
        assertEquals("s1", edge.fromId)
        assertEquals(protocolA, edge.toId)
    }

    @Test
    fun `two inputs on the same protocol collapse to one node and one edge`() {
        val wf = workflowWith(
            steps = listOf(
                stepConsuming("s1", protocolInput("hr", heartRate), protocolInput("steps", stepCount)),
            ),
            environments = mapOf("env1" to systemEnv),
        )

        val graph = LineageGraphBuilder.build(wf)
        assertEquals(1, graph.nodes.count { it.type == "protocol" })
        assertEquals(1, graph.edges.count { it.relation == "CONSUMES_FROM" })
    }

    @Test
    fun `steps consuming different protocols each get their own node and edge`() {
        val wf = workflowWith(
            steps = listOf(
                stepConsuming("s1", protocolInput("hr", heartRate, protocolId = protocolA)),
                stepConsuming("s2", protocolInput("steps", stepCount, protocolId = protocolB)),
            ),
            environments = mapOf("env1" to systemEnv),
        )

        val graph = LineageGraphBuilder.build(wf)
        assertEquals(2, graph.nodes.count { it.type == "protocol" })
        assertEquals(
            listOf("s1" to protocolA, "s2" to protocolB),
            graph.edges.filter { it.relation == "CONSUMES_FROM" }.map { it.fromId to it.toId },
        )
    }

    @Test
    fun `workflow with no protocol-bound inputs has no protocol nodes or edges`() {
        val wf = workflowWith(
            steps = listOf(
                DefinedStepDescriptor(id = "s1", environmentId = "env1", task = CommandTaskDescriptor(name = "s1", executable = "echo")),
            ),
            environments = mapOf("env1" to systemEnv),
        )

        val graph = LineageGraphBuilder.build(wf)
        assertTrue(graph.nodes.none { it.type == "protocol" })
        assertTrue(graph.edges.none { it.relation == "CONSUMES_FROM" })
    }

    @Test
    fun `graph with protocol nodes still conforms`() {
        val wf = workflowWith(
            steps = listOf(stepConsuming("s1", protocolInput("hr", heartRate))),
            environments = mapOf("env1" to condaEnv),
        )

        assertTrue(LineageConformance.validate(LineageGraphBuilder.build(wf)).isEmpty())
    }
}
