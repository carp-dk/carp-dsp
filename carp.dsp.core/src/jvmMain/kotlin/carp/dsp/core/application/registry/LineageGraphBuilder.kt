package carp.dsp.core.application.registry

import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import health.workflows.interfaces.api.LineageEdge
import health.workflows.interfaces.api.LineageGraph
import health.workflows.interfaces.api.LineageNode

/**
 * Builds a [LineageGraph] from a [WorkflowDescriptor].
 *
 * Produces three node types:
 * - `"step"` — one per workflow step
 * - `"environment"` — one per declared environment
 * - `"package"` — one per dependency listed in an environment's spec
 */
object LineageGraphBuilder {

    fun build(descriptor: WorkflowDescriptor): LineageGraph {
        val nodes = mutableListOf<LineageNode>()
        val edges = mutableListOf<LineageEdge>()

        // -- Environment and package nodes -------------------------------------

        descriptor.environments.forEach { (envId, env) ->
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

        // -- Step nodes + USES edges ------------------------------------------

        descriptor.steps.forEach { step ->
            val stepId = step.id ?: step.task.name
            val stepVersion = step.metadata?.version ?: ""
            nodes.add(LineageNode(id = stepId, version = stepVersion, type = "step", label = step.task.name))

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

        return LineageGraph(nodes = nodes, edges = edges)
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
