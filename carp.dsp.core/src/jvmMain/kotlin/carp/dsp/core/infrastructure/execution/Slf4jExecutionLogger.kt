package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.ExecutionLogger
import dk.cachet.carp.common.application.UUID
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC

/**
 * SLF4J-backed implementation of [ExecutionLogger].
 *
 * Sets MDC keys `runId` and `stepId` before each step so that all log output
 * produced during that step (including stdout/stderr from [carp.dsp.core.infrastructure.runtime.JvmCommandRunner])
 * carries the relevant context. MDC is cleared after each step completes or fails.
 */
class Slf4jExecutionLogger : ExecutionLogger {

    private val logger = KotlinLogging.logger {}

    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) {
        MDC.put("runId", runId.toString())
        MDC.put("stepId", stepId.toString())
        logger.info { "[$stepName] started" }
    }

    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) {
        logger.info { "[$stepName] completed in ${durationMs}ms" }
        MDC.clear()
    }

    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) {
        logger.warn { "[$stepName] failed - $reason" }
        MDC.clear()
    }
}
