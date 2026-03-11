package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * Resolves environment definitions to polymorphic EnvironmentRef implementations.
 */
class EnvironmentRefResolver {

    fun resolveEnvironments(
        steps: List<Step>,
        definitions: Map<UUID, EnvironmentDefinition>,
        issues: MutableList<PlanIssue>
    ): Map<UUID, EnvironmentRef> {
        val resolved = mutableMapOf<UUID, EnvironmentRef>()
        val uniqueEnvIds = steps.map { it.environmentId }.distinct()

        for (envId in uniqueEnvIds) {
            val definition = definitions[envId]
            if (definition == null) {
                issues.add(
                    PlanIssue(
                        severity = PlanIssueSeverity.ERROR,
                        code = "MISSING_ENVIRONMENT_DEFINITION",
                        message = "Environment '$envId' referenced by step(s) but not defined.",
                        stepId = null
                    )
                )
            } else {
                val envRef = createEnvironmentRef(envId, definition)
                if (envRef != null) {
                    resolved[envId] = envRef
                } else {
                    issues.add(
                        PlanIssue(
                            severity = PlanIssueSeverity.ERROR,
                            code = "UNSUPPORTED_ENVIRONMENT_KIND",
                            message = "Environment '$envId' has unsupported kind.",
                            stepId = null
                        )
                    )
                }
            }
        }

        return resolved
    }

    private fun createEnvironmentRef(
        envId: UUID,
        definition: EnvironmentDefinition
    ): EnvironmentRef? {
        val id = envId.toString()

        return when (definition) {
            is CondaEnvironmentDefinition -> {
                CondaEnvironmentRef(
                    id = id,
                    name = definition.name,
                    dependencies = definition.dependencies,
                    channels = definition.channels,
                    pythonVersion = definition.pythonVersion
                )
            }
            is PixiEnvironmentDefinition -> {
                PixiEnvironmentRef(
                    id = id,
                    dependencies = definition.dependencies,
                    pythonVersion = definition.pythonVersion
                )
            }
            is SystemEnvironmentDefinition -> {
                SystemEnvironmentRef(
                    id = id,
                    dependencies = definition.dependencies
                )
            }
            else -> null
        }
    }
}
