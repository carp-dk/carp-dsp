package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.*
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedOutput
import dk.cachet.carp.analytics.domain.data.*
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
 */
interface ArtefactRecorder
{
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
 *
 */
class FileSystemArtefactRecorder(
    private val pathResolver: FilePathResolver = DefaultFilePathResolver(),
    private val hashCalculator: FileHashCalculator = DefaultFileHashCalculator(),
    private val contentTypeDetector: ContentTypeDetector = ExtensionBasedContentTypeDetector
) : ArtefactRecorder
{
    override fun recordArtefacts(
        step: PlannedStep,
        stepWorkingDir: Path,
        artefactStore: ArtefactStore
    ): List<ProducedOutputRef> =
        step.bindings.outputs.values.mapNotNull { output ->
            recordOutputArtefact( step, output, stepWorkingDir, artefactStore )
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
        output: ResolvedOutput,
        stepWorkingDir: Path,
        artefactStore: ArtefactStore
    ): ProducedOutputRef?
    {
        // Determine the data file path from DataLocation
        val dataFile = pathResolver.resolveOutputPath( output.location, stepWorkingDir )

        // File must exist and be regular (not directory)
        if ( !Files.exists( dataFile ) || !Files.isRegularFile( dataFile ) )
        {
            System.err.println(
                "ArtefactRecorder: File not found: $dataFile (location: ${output.location})"
            )
            return null
        }

        return try
        {
            // Calculate metadata
            val sizeBytes = Files.size( dataFile )
            val sha256 = hashCalculator.calculateHash( dataFile )

            // Determine content type from file extension
            val contentType = contentTypeDetector.detect(
                dataFile.fileName.toString(),
                output.spec.format?.mimeType
            )

            // Get the resolved path from FileLocation
            val resolvedPath = (output.location as? FileLocation)?.path
                ?: "steps/${step.metadata.id}/outputs/${output.spec.id}/data"

            // Record to store
            artefactStore.recordArtefact(
                stepId = step.metadata.id,
                outputId = output.spec.id,
                location = ResourceRef(
                    kind = ResourceKind.RELATIVE_PATH,
                    value = resolvedPath
                ),
                metadata = ArtefactMetadata(
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    contentType = contentType
                )
            )
        }
        catch ( _: Exception )
        {
            // Fail silently - incomplete Artefacts are not fatal
            null
        }
    }
}

/**
 * Converts DataLocation to absolute file system path.
 * Handles all 6 location types + resolves relative paths.
 */
interface FilePathResolver
{
    fun resolveOutputPath( location: DataLocation, stepWorkingDir: Path ): Path
}

/**
 * Default path resolver using FileLocation.resolve() strategy.
 *
 * For FileLocation: uses resolve() method for smart path generation
 * For other types: returns a placeholder path (cannot write to non-file locations)
 */
class DefaultFilePathResolver : FilePathResolver
{
    override fun resolveOutputPath( location: DataLocation, stepWorkingDir: Path ): Path
    {
        return when ( location )
        {
            is FileLocation ->
            {
                val path = Path.of( location.path )
                if ( path.isAbsolute ) path else stepWorkingDir.resolve( location.path )
            }

            is InMemoryLocation ->
            {
                // Cannot write in-memory data to file system
                stepWorkingDir.resolve( "outputs" ).resolve( location.registryKey )
            }

            is UrlLocation, is DatabaseLocation, is ApiLocation, is StreamLocation ->
            {
                // Cannot write remote/streaming data to local file system
                stepWorkingDir.resolve( "outputs" ).resolve( "unresolved-location" )
            }
        }
    }
}

/**
 * Computes file hashes independently of artefact recording logic.
 * Easy to unit test, easy to swap implementations (MD5, SHA1, SHA512, etc.).
 */
interface FileHashCalculator
{
    fun calculateHash( file: Path ): String
}

/**
 * SHA-256 hash calculator.
 *
 * Uses streaming to avoid loading entire file into memory.
 * Suitable for large files.
 */
class DefaultFileHashCalculator : FileHashCalculator
{
    override fun calculateHash( file: Path ): String
    {
        val digest = MessageDigest.getInstance( "SHA-256" )
        Files.newInputStream( file ).use { inputStream ->
            val buffer = ByteArray( BUFFER_SIZE )
            var bytesRead: Int
            while ( inputStream.read( buffer ).also { bytesRead = it } != -1 )
            {
                digest.update( buffer, 0, bytesRead )
            }
        }
        return digest.digest().joinToString( "" ) { "%02x".format( it ) }
    }

    companion object
    {
        private const val BUFFER_SIZE = 8192
    }
}
