package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.exceptions.ArtefactCollectionException
import dk.cachet.carp.analytics.application.exceptions.ExecutionIOException
import dk.cachet.carp.analytics.application.execution.ArtefactMetadata
import dk.cachet.carp.analytics.application.execution.ArtefactRecord
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.ExperimentalTime

/**
 * File-system based implementation of [ArtefactStore].
 *
 * Maintains a registry in memory and persists metadata.json per artefact.
 *
 * @param workspaceRoot The root directory of the execution workspace
 * @param clock Wall-clock source for timestamps; defaults to [Clock.System]
 */
class FileSystemArtefactStore(
    private val workspaceRoot: Path,
    private val clock: Clock.System = Clock.System
) : ArtefactStore
{
    private val outputsDir: Path = workspaceRoot.resolve("outputs")
    private val _registry = mutableMapOf<UUID, ArtefactRecord>()
    private val json = Json { prettyPrint = true }

    init
    {
        outputsDir.createDirectories()
        // Load any existing Artefacts from disk
        loadExistingArtefacts()
    }

    @OptIn(ExperimentalTime::class)
    override fun recordArtefact(
        stepId: UUID,
        outputId: UUID,
        location: ResourceRef,
        metadata: ArtefactMetadata
    ): ProducedOutputRef?
    {
        return try
        {
            val producedRef = ProducedOutputRef(
                outputId = outputId,
                location = location,
                sizeBytes = metadata.sizeBytes,
                sha256 = metadata.sha256,
                contentType = metadata.contentType
            )

            val record = ArtefactRecord(
                stepId = stepId,
                outputId = outputId,
                producedOutputRef = producedRef,
                recordedAt = clock.now()
            )

            // Store in registry
            _registry[outputId] = record

            // Persist metadata
            persistMetadata(record)

            producedRef
        }
        catch (e: IOException) {
            throw ExecutionIOException(
                message = "Failed to record artifact",
                filePath = location.value,
                cause = e
            )
            // TODO Add logging
        }
        catch (e: IllegalArgumentException) {
            throw ArtefactCollectionException(
                message = "Invalid artifact metadata for outputId=$outputId: ${e.message}",
                artefactId = outputId,
                cause = e
            )
            // TODO Add logging
        }
    }

    override fun getArtefact( outputId: UUID ): ProducedOutputRef? =
        _registry[outputId]?.producedOutputRef

    override fun getArtefactsByStep( stepId: UUID ): List<ProducedOutputRef> =
        _registry.values
            .filter { it.stepId == stepId }
            .map { it.producedOutputRef }

    override fun getAllArtefacts(): List<ProducedOutputRef> =
        _registry.values.map { it.producedOutputRef }

    override fun resolvePath( outputId: UUID ): String?
    {
        val artefact = getArtefact(outputId) ?: return null
        return when (artefact.location.kind)
        {
            ResourceKind.RELATIVE_PATH -> workspaceRoot.resolve(artefact.location.value).toString()
            ResourceKind.URI -> artefact.location.value
        }
    }

    // Persistence

    private fun getArtefactDir( stepId: UUID, outputId: UUID ): Path =
        outputsDir.resolve(stepId.toString()).resolve(outputId.toString())

    @OptIn(ExperimentalTime::class)
    private fun persistMetadata( record: ArtefactRecord )
    {
        val dir = getArtefactDir(record.stepId, record.outputId)
        dir.createDirectories()

        val persisted = PersistedArtefactMetadata(
            outputId = record.outputId,
            stepId = record.stepId,
            location = record.producedOutputRef.location,
            sizeBytes = record.producedOutputRef.sizeBytes,
            sha256 = record.producedOutputRef.sha256,
            contentType = record.producedOutputRef.contentType,
            recordedAt = record.recordedAt
        )
        dir.resolve("metadata.json").writeText(
            json.encodeToString(PersistedArtefactMetadata.serializer(), persisted)
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun loadExistingArtefacts()
    {
        if (!outputsDir.exists() || !outputsDir.isDirectory()) return

        try
        {
            outputsDir.toFile().walkTopDown()
                .map { it.toPath().resolve("metadata.json") }
                .filter { it.exists() }
                .forEach { tryLoadArtefact(it) }
        }
        catch (_: IOException)
        {
            // TODO Add logging
            /* Failed to walk directory - graceful degradation */
        }
        catch (_: SecurityException) {
            // TODO Add logging
            /* Insufficient permissions to read - graceful degradation */
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun tryLoadArtefact( metadataFile: Path )
    {
        try
        {
            val persisted = json.decodeFromString(
                PersistedArtefactMetadata.serializer(),
                metadataFile.readText()
            )
            val record = ArtefactRecord(
                stepId = persisted.stepId,
                outputId = persisted.outputId,
                producedOutputRef = ProducedOutputRef(
                    outputId = persisted.outputId,
                    location = persisted.location,
                    sizeBytes = persisted.sizeBytes,
                    sha256 = persisted.sha256,
                    contentType = persisted.contentType
                ),
                recordedAt = persisted.recordedAt
            )
            _registry[persisted.outputId] = record
        }
        catch (_: IOException)
        {
            // TODO: add logs - collect logs for ExecutionReport
            // _logs.add(ExecutionLog(level = "WARN", message = "Failed to walk artifact directory", cause = e))
        }
        catch (_: SecurityException)
        {
            // TODO: add logs - collect logs for ExecutionReport
            // _logs.add(ExecutionLog(level = "WARN", message = "No read permission for artifact directory", cause = e))
        }
    }

    // Serializable metadata for persistence

    @kotlinx.serialization.Serializable
    private data class PersistedArtefactMetadata(
        val outputId: UUID,
        val stepId: UUID,
        val location: ResourceRef,
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val contentType: String? = null,
        val recordedAt: Instant
    )
}
