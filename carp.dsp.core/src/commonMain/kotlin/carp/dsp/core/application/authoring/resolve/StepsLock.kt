package carp.dsp.core.application.authoring.resolve

import kotlinx.serialization.Serializable

/**
 * The resolution of one `uses:` reference, pinned for reproducibility.
 *
 * @property uses The reference id, e.g. `"sensing.heartrate.clean"`.
 * @property version The concrete version the reference resolved to.
 * @property contentHash Hash of the resolved step's content, verified on
 *   re-resolution so the library cannot serve different bytes under the same
 *   version without being noticed.
 */
@Serializable
data class LockedStep(
    val uses: String,
    val version: String,
    val contentHash: String,
)

/**
 * The recorded resolution of a workflow's `uses:` references - committed beside
 * the workflow as `steps.lock`.
 *
 * A bare reference resolves to the latest and is pinned here; re-resolving with
 * the lock present reuses the pinned version and verifies the content hash, so
 * planning the same workflow later produces the same plan (NF1).
 */
@Serializable
data class StepsLock(
    val steps: List<LockedStep> = emptyList(),
)
{
    fun entryFor( uses: String ): LockedStep? = steps.firstOrNull { it.uses == uses }
}
