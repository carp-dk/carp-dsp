package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataRequest
import carp.dsp.core.application.StudyDataSource
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.data.application.DataStreamBatch
import dk.cachet.carp.data.application.DataStreamService
import dk.cachet.carp.data.application.MutableDataStreamBatch
import kotlin.time.Instant

/**
 * A [StudyDataSource] backed by CARP's data service.
 */
class CarpStudyDataSource(
    private val dataStreams: DataStreamService,
) : StudyDataSource
{
    /**
     * Returns measurements matching [request] for the specified [targets].
     *
     * Results are filtered by data type and time range. The upper time bound
     * is exclusive.
     */
    override suspend fun fetch(request: StudyDataRequest, targets: List<DeploymentTarget>): DataStreamBatch
    {
        // Empty means "no filter".
        val dataTypes = request.dataTypes.map { DataType.fromString(it) }.toSet().ifEmpty { null }
        val from = request.fromMs?.let { Instant.fromEpochMilliseconds(it) }
        val to = request.toMs?.let { Instant.fromEpochMilliseconds(it) }

        val merged = MutableDataStreamBatch()
        groupByRole(targets).forEach { (role, deployments) ->
            merged.appendBatch(
                dataStreams.getBatchForStudyDeployments(
                    studyDeploymentIds = deployments,
                    deviceRoleNames = role?.let { setOf(it) },
                    dataTypes = dataTypes,
                    from = from,
                    to = to,
                )
            )
        }
        return merged
    }
}

/**
 * Groups deployment targets by device role.
 */
internal fun groupByRole(targets: List<DeploymentTarget>): Map<String?, Set<UUID>> =
    targets.groupBy({ it.deviceRoleName }, { it.studyDeploymentId })
        .mapValues { (_, deployments) -> deployments.toSet() }
