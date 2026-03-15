package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.LogRecord
import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.execution.StepLogRecorder
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.ExperimentalTime

/**
 * File system based step log recording.
 *
 * Records combined stdout/stderr to timestamped log files.
 *
 * Layout:
 * ```
 * workspace/
 *   logs/
 *     {stepId}-{timestamp}.log
 * ```
 *
 * Log format:
 * ```
 * === STDOUT ===
 * (stdout content)
 * === STDERR ===
 * (stderr content)
 * ```
 *
 * @param clock Source of wall-clock instants; defaults to [Clock.System]
 */
class FileSystemStepLogRecorder(
    private val clock: Clock = Clock.System
) : StepLogRecorder {

    @OptIn(ExperimentalTime::class)
    override fun recordLogs(
        step: PlannedStep,
        result: CommandResult,
        workspace: ExecutionWorkspace
    ): ResourceRef? {
        // Skip if no output to record
        if (result.stdout.isBlank() && result.stderr.isBlank()) {
            return null
        }

        return try {
            val logFile = createLogFile(workspace, step.stepId)
            writeLogContent(logFile, result)

            val location = ResourceRef(
                kind = ResourceKind.RELATIVE_PATH,
                value = "logs/${logFile.fileName}"
            )

            // Track internally (for future extensions like indexing)
            _records[step.stepId] = LogRecord(
                stepId = step.stepId,
                location = location,
                hasStdout = result.stdout.isNotBlank(),
                hasStderr = result.stderr.isNotBlank(),
                recordedAt = clock.now()
            )

            location
        } catch (_: Exception) {
            // Fail silently - incomplete logs are not fatal
            null
        }
    }


    // Private Helpers


    private val _records = mutableMapOf<UUID, LogRecord>()

    /**
     * Create log file with timestamped name.
     *
     * Format: {stepId}-{timestamp}.log
     * Example: 550e8400-e29b-41d4-a716-446655440000-2026-03-16T14-32-45.123456Z.log
     */
    private fun createLogFile(
        workspace: ExecutionWorkspace,
        stepId: UUID
    ): Path {
        val logsDir = Paths.get(workspace.executionRoot).resolve("logs")
        logsDir.createDirectories()

        val timestamp = clock.now().toString()
            .replace(":", "-") // Replace time separators
            .replace(".", "-") // Replace decimal point

        val fileName = "$stepId-$timestamp.log"
        return logsDir.resolve(fileName)
    }

    /**
     * Write log content to file.
     *
     * Format:
     * === STDOUT ===
     * (stdout content)
     * === STDERR ===
     * (stderr content)
     */
    private fun writeLogContent(logFile: Path, result: CommandResult) {
        val content = buildString {
            if (result.stdout.isNotBlank()) {
                appendLine("=== STDOUT ===")
                appendLine(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                if (result.stdout.isNotBlank()) {
                    appendLine() // Blank line separator
                }
                appendLine("=== STDERR ===")
                appendLine(result.stderr)
            }
        }
        logFile.writeText(content)
    }

    /**
     * Get all recorded logs (internal tracking).
     *
     * Useful for testing or debugging.
     */
    internal fun getAllRecords(): List<LogRecord> = _records.values.toList()

    /**
     * Get records for a specific step.
     */
    internal fun getRecords(stepId: UUID): LogRecord? = _records[stepId]
}
