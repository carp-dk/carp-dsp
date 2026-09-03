package carp.dsp.core.application

import carp.dsp.core.domain.data.CarpTabularData

/**
 * Reads study data and converts it to [CarpTabularData].
 *
 * Data is retrieved from a [StudyDataSource] and converted using a
 * [DataStreamBatchConverter].
 */
class StudyDataReader(
    private val source: StudyDataSource,
    private val deployments: StudyDeploymentSource? = null,
    private val converter: DataStreamBatchConverter = DataStreamBatchConverter(),
)
{
    /**
     * Returns the data matching [request].
     *
     * @throws IllegalStateException If no targets are specified and deployments
     * cannot be resolved for the study.
     */
    suspend fun read(request: StudyDataRequest): CarpTabularData
    {
        val targets = resolveTargets(request)
        return converter.toTabularData(source.fetch(request, targets))
    }

    private suspend fun resolveTargets(request: StudyDataRequest): List<DeploymentTarget>
    {
        if (request.targets.isNotEmpty()) return request.targets

        val resolver = checkNotNull(deployments)
        {
            "Request for study '${request.studyId}' names no deployments and no " +
                "StudyDeploymentSource was supplied to resolve them."
        }
        // No role: an unnarrowed request reads every role the deployment has.
        val resolved = resolver.deploymentsOf(request.studyId).map { DeploymentTarget(it) }

        check(resolved.isNotEmpty()) { "Study '${request.studyId}' has no deployments to read." }
        return resolved
    }
}
