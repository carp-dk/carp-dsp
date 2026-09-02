package carp.dsp.core.application.registry

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.ProtocolInputSource
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.packaging.protocolReferences
import health.workflows.interfaces.api.LineageEdge
import health.workflows.interfaces.api.LineageGraph
import health.workflows.interfaces.api.LineageNode

/**
 * Builds a [LineageGraph] from a [WorkflowDescriptor].
 *
 * Produces four node types:
 * - `"step"` — one per workflow step
 * - `"environment"` — one per declared environment
 * - `"package"` — one per dependency listed in an environment's spec
 * - `"protocol"` — one per study protocol the workflow draws data from
 */
object LineageGraphBuilder {

    fun build(descriptor: WorkflowDescriptor): LineageGraph {
        val nodes = mutableListOf<LineageNode>()
        val edges = mutableListOf<LineageEdge>()

        descriptor.environments.forEach { (envId, env) ->
            addEnvironment(envId, env, nodes, edges)
        }
        addProtocols(descriptor, nodes)
        descriptor.steps.forEach { step ->
            addStep(descriptor, step, nodes, edges)
        }

        return LineageGraph(nodes = nodes, edges = edges)
    }

    // -- Node builders ---------------------------------------------------------

    /** An environment node, plus a `CONTAINS` edge to each package it declares. */
    private fun addEnvironment(
        envId: String,
        env: EnvironmentDescriptor,
        nodes: MutableList<LineageNode>,
        edges: MutableList<LineageEdge>,
    ) {
        val envVersion = envVersionOf(env)
        nodes.add(LineageNode(id = envId, version = envVersion, type = "environment", label = env.name))

        env.spec["dependencies"]?.forEach { pkg ->
            val pkgId = "$envId:$pkg"
            nodes.add(LineageNode(id = pkgId, version = "", type = "package", label = pkg))
            edges.add(
                LineageEdge(
                    fromId = envId,
                    fromVersion = envVersion,
                    toId = pkgId,
                    toVersion = "",
                    relation = "CONTAINS"
                )
            )
        }
    }

    /** One node per referenced study protocol, even when several inputs bind to it. */
    private fun addProtocols(descriptor: WorkflowDescriptor, nodes: MutableList<LineageNode>) {
        descriptor.protocolReferences().forEach { protocol ->
            nodes.add(
                LineageNode(
                    id = protocol.id,
                    version = protocol.version?.toString() ?: "",
                    type = "protocol",
                    label = protocol.name ?: protocol.id
                )
            )
        }
    }

    /**
     * A step node, a `USES` edge to its environment, and a `CONSUMES_FROM` edge to
     * each study protocol it takes input data from.
     */
    private fun addStep(
        descriptor: WorkflowDescriptor,
        step: StepDescriptor,
        nodes: MutableList<LineageNode>,
        edges: MutableList<LineageEdge>,
    ) {
        // A referenced step (`uses:`) has no inline task/environment until resolved;
        // fall back to its reference for identity and skip the environment edge.
        val label = when (step) {
            is DefinedStepDescriptor -> step.task.name
            is ReferencedStepDescriptor -> step.uses
        }
        val stepId = step.id ?: label
        val stepVersion = step.metadata?.version ?: ""
        nodes.add(LineageNode(id = stepId, version = stepVersion, type = "step", label = label))

        if (step is DefinedStepDescriptor) {
            val envId = step.environmentId
            val envVersion = descriptor.environments[envId]?.let { envVersionOf(it) } ?: ""
            edges.add(
                LineageEdge(
                    fromId = stepId,
                    fromVersion = stepVersion,
                    toId = envId,
                    toVersion = envVersion,
                    relation = "USES"
                )
            )
        }

        // Inputs bound to the same protocol collapse to a single edge.
        step.inputs
            .mapNotNull { it.source as? ProtocolInputSource }
            .distinctBy { it.protocol.id to it.protocol.version }
            .forEach { source ->
                edges.add(
                    LineageEdge(
                        fromId = stepId,
                        fromVersion = stepVersion,
                        toId = source.protocol.id,
                        toVersion = source.protocol.version?.toString() ?: "",
                        relation = "CONSUMES_FROM"
                    )
                )
            }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Extracts a display version from an [EnvironmentDescriptor].
     * Priority: pythonVersion > rVersion > image tag > empty string.
     */
    private fun envVersionOf(env: EnvironmentDescriptor): String =
        env.spec["pythonVersion"]?.firstOrNull()
            ?: env.spec["rVersion"]?.firstOrNull()
            ?: env.spec["image"]?.firstOrNull()
            ?: ""
}
