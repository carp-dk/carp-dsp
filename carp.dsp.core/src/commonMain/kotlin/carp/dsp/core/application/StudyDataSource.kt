package carp.dsp.core.application

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.data.application.DataStreamBatch

/**
 * Provides deployment IDs for a study.
 */
fun interface StudyDeploymentSource

{
    /**
     * Returns the deployment IDs associated with [studyId].
     */
    suspend fun deploymentsOf(studyId: UUID): Set<UUID>
}

/**
 * Provides access to study data.
 */
fun interface StudyDataSource
{
    /**
     * Returns measurements matching [request] for the specified [targets].
     *
     * ** An*empty batch indicates that no measurements matched the request.
     */
    suspend fun fetch(request: StudyDataRequest, targets: List<DeploymentTarget>): DataStreamBatch
}
