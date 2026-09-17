package carp.dsp.core.application

import dk.cachet.carp.common.application.UUID

/**
 * Identifies a deployment from which data should be read.
 *
 * If [deviceRoleName] is specified, data is limited to that device role.
 * Otherwise, data from all device roles in the deployment is included.
 */
data class DeploymentTarget(
    val studyDeploymentId: UUID,
    val deviceRoleName: String? = null,
)

/**
 * Describes a study data query.
 *
 * Filters can be applied by deployment, device role, data type, and time range.
 *
 * @property studyId The study to read. Configured explicitly for now; deriving
 *   it from the user's own studies is a later concern.
 * @property targets Deployments, each optionally narrowed to one device role.
 *   Empty means every deployed group in the study.
 * @property dataTypes Fully namespaced CARP data types (e.g.
 *   `"dk.cachet.carp.heartrate"`). Empty means every type collected.
 * @property fromMs Start of the window, epoch milliseconds, inclusive. `null`
 *   leaves it open.
 * @property toMs End of the window, epoch milliseconds. `null` leaves it open.
 *   Note CARP's own range is half-open, so a measurement exactly at [toMs] is
 *   excluded; see the source implementation for how this is passed on.
 * @property refreshCache Re-read from the study rather than serving a previous
 *   read of the same request.
 */
data class StudyDataRequest(
    val studyId: UUID,
    val targets: List<DeploymentTarget> = emptyList(),
    val dataTypes: Set<String> = emptySet(),
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val refreshCache: Boolean = false,
)
{
    init
    {
        require(fromMs == null || toMs == null || fromMs <= toMs)
        {
            "A study data request cannot end before it starts (from=$fromMs, to=$toMs)."
        }
    }
}
