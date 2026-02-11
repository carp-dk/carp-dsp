package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

/**
 * A narrow, executor-facing description of what will be executed for a planned step.
 *
 * This is an application-layer runtime planning model (Plan → Run).
 */
@Serializable
sealed interface ProcessRun
