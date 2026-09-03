package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID

/** Output formats the table writer can produce. */
enum class StudyDataFormat { CSV }

private const val TARGET_SEPARATOR = ':'
private const val COMMENT = '#'

/**
 * Configuration for the fetch-study-data step.
 *
 * @property request The study data request to execute.
 * @property targetsFile A file containing deployment targets.
 * @property format The output format.
 * @property outputPath The measurement output path.
 * @property provenancePath The provenance output path.
 * @property columns The columns to include in the output.
 * @property services Which [StudyServices] to fetch through, by
 *   [StudyServicesFactory] name.
 */
data class StudyDataStepConfig(
    val request: StudyDataRequest,
    val targetsFile: String? = null,
    val format: StudyDataFormat = StudyDataFormat.CSV,
    val outputPath: String,
    val provenancePath: String? = null,
    val columns: List<String>? = null,
    val services: String = StudyServicesFactory.IN_MEMORY,
)

/**
 * Parses deployment targets from a targets file.
 *
 * Each non-empty, non-comment line must be in the form
 * `<deployment-id>` or `<deployment-id>:<device-role>`.
 *
 * @throws IllegalArgumentException If a line cannot be parsed.
 */
fun parseTargets(lines: List<String>): List<DeploymentTarget> =
    lines.mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith(COMMENT)) return@mapIndexedNotNull null

        val id = line.substringBefore(TARGET_SEPARATOR).trim()
        val role = line.substringAfter(TARGET_SEPARATOR, "").trim().ifEmpty { null }

        val deploymentId = runCatching { UUID.parse(id) }.getOrElse {
            throw IllegalArgumentException(
                "Line ${index + 1} of the targets file is not a deployment id: '$raw'."
            )
        }
        DeploymentTarget(deploymentId, role)
    }
