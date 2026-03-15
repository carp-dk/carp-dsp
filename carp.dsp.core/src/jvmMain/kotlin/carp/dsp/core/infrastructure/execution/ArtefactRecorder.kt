package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.*
import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.PlannedStep
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Records Artefacts produced by step execution.
 *
 * Handles:
 * - Scanning output directories
 * - Calculating Artefact metadata (size, hash)
 * - Registering with Artefact store
 *
 * Can be extended with different strategies:
 * - FileSystem (current)
 * - S3-backed (future)
 * - RemoteRegistry (future)
 */
interface ArtefactRecorder {
    /**
     * Record all Artefacts produced by a step.
     *
     * @param step The step that was executed
     * @param stepWorkingDir Absolute path to step's working directory
     * @param artefactStore Where to record Artefacts
     * @return List of successfully recorded Artefacts
     */
    fun recordArtefacts(
        step: PlannedStep,
        stepWorkingDir: Path,
        artefactStore: ArtefactStore
    ): List<ProducedOutputRef>
}

/**
 * File system based Artefact recording.
 *
 * Records Artefacts from files in the step's output directory.
 * Calculates SHA-256 hash and determines content type.
 */
class FileSystemArtefactRecorder(
    private val contentTypeDetector: ContentTypeDetector = ExtensionBasedContentTypeDetector
) : ArtefactRecorder {

    override fun recordArtefacts(
        step: PlannedStep,
        stepWorkingDir: Path,
        artefactStore: ArtefactStore
    ): List<ProducedOutputRef> =
        step.bindings.outputs.values.mapNotNull { output ->
            recordOutputArtefact(step, output, stepWorkingDir, artefactStore)
        }

    /**
     * Record a single output Artefact.
     *
     * Fails silently (returns null) if:
     * - Output file doesn't exist
     * - File is not a regular file
     * - Any error during recording
     */
    private fun recordOutputArtefact(
        step: PlannedStep,
        output: DataRef,
        stepWorkingDir: Path,
        artefactStore: ArtefactStore
    ): ProducedOutputRef? {
        val dataFile = stepWorkingDir
            .resolve("outputs")
            .resolve(output.id.toString())
            .resolve("data")

        // File must exist and be regular (not directory)
        if (!Files.exists(dataFile) || !Files.isRegularFile(dataFile)) {
            return null
        }

        return try {
            // Calculate metadata
            val sizeBytes = Files.size(dataFile)
            val sha256 = calculateSha256(dataFile)
            val contentType = contentTypeDetector.detect(
                dataFile.fileName.toString(),
                output.type
            )

            // Record to store
            artefactStore.recordArtefact(
                stepId = step.stepId,
                outputId = output.id,
                location = ResourceRef(
                    kind = ResourceKind.RELATIVE_PATH,
                    value = "steps/${step.stepId}/outputs/${output.id}/data"
                ),
                metadata = ArtefactMetadata(
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    contentType = contentType
                )
            )
        } catch (_: Exception) {
            // Fail silently - incomplete Artefacts are not fatal
            null
        }
    }

    /**
     * Calculate SHA-256 hash of a file.
     *
     * Uses streaming to avoid loading entire file into memory,
     * suitable for large files.
     */
    private fun calculateSha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { inputStream ->
            val buffer = ByteArray(SHA256_BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SHA256_BUFFER_SIZE = 8192
    }
}
