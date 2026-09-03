package carp.dsp.steps.data

import carp.dsp.core.application.StudyDeploymentSource
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.studies.application.RecruitmentService
import dk.cachet.carp.studies.application.users.ParticipantGroupStatus

/**
 * A [StudyDeploymentSource] backed by CARP's recruitment service.
 */
class CarpStudyDeploymentSource(
    private val recruitment: RecruitmentService,
) : StudyDeploymentSource
{
    /**
     * Returns the deployment IDs for participant groups in [studyId] that have
     * entered deployment.
     */
    override suspend fun deploymentsOf(studyId: UUID): Set<UUID> =
        recruitment.getParticipantGroupStatusList(studyId)
            .filterIsInstance<ParticipantGroupStatus.InDeployment>()
            .map { it.studyDeploymentStatus.studyDeploymentId }
            .toSet()
}
