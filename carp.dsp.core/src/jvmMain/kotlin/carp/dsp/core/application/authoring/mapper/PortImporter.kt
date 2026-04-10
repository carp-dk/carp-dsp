 package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import dk.cachet.carp.analytics.domain.data.DataSchema
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InMemoryLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.common.application.UUID


/**
 * Maps `DataPortDescriptor` to `InputDataSpec` / `OutputDataSpec`.
 *
 * Uses the unified DataLocation model:
 * - FileInputSource → FileLocation
 * - StepOutputInputSource → FileLocation (with empty path) + stepRef
 * - EnvironmentVariableInputSource → InMemoryLocation
 * - FileOutputDestination → FileLocation
 * - EnvironmentVariableOutputDestination → InMemoryLocation
 */
internal object PortImporter
{
    /**
     * Maps a data port descriptor to an input data spec.
     *
     * ### Source Resolution (Using DataLocation Model)
     *
     * - [FileInputSource] → [FileLocation] (with file path)
     * - [StepOutputInputSource] → [FileLocation] (empty path) with stepRef set
     * - [EnvironmentVariableInputSource] → [InMemoryLocation]
     * - null (no source specified) → [FileLocation] (empty path, no stepRef)
     *
     * The stepRef field indicates whether the input comes from another step.
     * Empty path will be resolved by BindingsResolver later.
     *
     * @param portDescriptor The input port descriptor
     * @return Domain InputDataSpec with mapped DataLocation
     */
    fun importInputPort(
        portDescriptor: DataPortDescriptor,
        workflowNamespace: UUID
    ): InputDataSpec
    {
        val portId = resolvePortId( portDescriptor.id, workflowNamespace, "input" )
        val (location, stepRef) = resolveInputLocation( portDescriptor )

        return InputDataSpec(
            id = portId,
            name = portDescriptor.id ?: portId.toString(),
            description = null,
            schema = importSchema( portDescriptor ),
            location = location,
            stepRef = stepRef,
            required = true,
            constraints = null
        )
    }

    /**
     * Resolves the location and stepRef for an input based on its source.
     */
    private fun resolveInputLocation(
        portDescriptor: DataPortDescriptor
    ): Pair<dk.cachet.carp.analytics.domain.data.DataLocation, String?>
    {
        return when ( val inputSource = portDescriptor.source )
        {
            is FileInputSource ->
            {
                val fileFormat = portDescriptor.descriptor?.type?.let { parseFileFormat( it ) }
                    ?: inferFormatFromPath( inputSource.path )
                Pair(
                    FileLocation(
                        path = inputSource.path,
                        format = fileFormat,
                        metadata = mapOf( "source" to "file" )
                    ),
                    null
                )
            }

            is StepOutputInputSource ->
            {
                Pair(
                    FileLocation(
                        path = "",
                        format = FileFormat.UNKNOWN,
                        metadata = mapOf( "source" to "step-output" )
                    ),
                    inputSource.stepId
                )
            }

            is EnvironmentVariableInputSource ->
            {
                Pair(
                    InMemoryLocation(
                        registryKey = inputSource.variableName,
                        metadata = mapOf( "source" to "environment" )
                    ),
                    null
                )
            }

            null ->
            {
                Pair(
                    FileLocation(
                        path = "",
                        format = FileFormat.UNKNOWN,
                        metadata = emptyMap()
                    ),
                    null
                )
            }
        }
    }

    /**
     * Maps a data port descriptor to an output data spec.
     *
     * ### Destination Mapping (Using DataLocation Model)
     *
     * - [FileOutputDestination] → [FileLocation]
     * - [EnvironmentVariableOutputDestination] → [InMemoryLocation]
     * - null (no destination specified) → [FileLocation] (empty path)
     *
     * @param portDescriptor The output port descriptor
     * @return Domain OutputDataSpec with mapped DataLocation
     */
    fun importOutputPort(
        portDescriptor: DataPortDescriptor,
        workflowNamespace: UUID
    ): OutputDataSpec
    {
        val portId = resolvePortId( portDescriptor.id, workflowNamespace, "output" )

        val location = when ( val outputDest = portDescriptor.destination )
        {
            is FileOutputDestination ->
            {
                val fileFormat = portDescriptor.descriptor?.type?.let { parseFileFormat( it ) }
                    ?: inferFormatFromPath( outputDest.path )
                FileLocation(
                    path = outputDest.path,
                    format = fileFormat,
                    metadata = mapOf( "destination" to "file" )
                )
            }

            is EnvironmentVariableOutputDestination ->
            {
                // Environment variable → InMemoryLocation
                InMemoryLocation(
                    registryKey = outputDest.variableName,
                    metadata = mapOf( "destination" to "environment" )
                )
            }

            null ->
            {
                // No destination specified → FileLocation (empty, will be generated)
                FileLocation(
                    path = "",
                    format = portDescriptor.descriptor?.type?.let { parseFileFormat( it ) }
                        ?: FileFormat.UNKNOWN,
                    metadata = emptyMap()
                )
            }
        }

        return OutputDataSpec(
            id = portId,
            name = portDescriptor.id ?: portId.toString(),
            description = null,
            schema = importSchema( portDescriptor ),
            location = location,
            format = null
        )
    }

    /**
     * Maps a data descriptor to a data schema.
     *
     * @param descriptor The data port descriptor
     * @return Domain DataSchema or null if type not specified
     */
    private fun importSchema( descriptor: DataPortDescriptor ): DataSchema? =
        descriptor.descriptor?.let {
            if ( it.type == null ) null
            else DataSchema(
                format = parseFileFormat( it.type ),
                encoding = it.format ?: "UTF-8",
            )
        }

    /**
     * Parses a file format string to [FileFormat] enum.
     *
     * Supports both enum names (e.g., "CSV", "JSON") and MIME types (e.g., "text/csv", "application/json").
     *
     * @param type The type string (case-insensitive) - can be enum name or MIME type
     * @return Matched FileFormat or FileFormat.UNKNOWN as fallback
     */
    private fun parseFileFormat( type: String ): FileFormat
    {
        val normalizedType = type.lowercase()

        // First, try to match by enum name
        FileFormat.entries.firstOrNull { it.name.equals( type, ignoreCase = true ) }?.let { return it }

        // Then, try to match by MIME type
        FileFormat.entries.firstOrNull { it.mimeType.lowercase() == normalizedType }?.let { return it }

        // If no exact match, try partial MIME type matching (for cases like "text/csv" matching "text/csv")
        FileFormat.entries.firstOrNull {
            it.mimeType.lowercase().contains( normalizedType ) || normalizedType.contains( it.mimeType.lowercase() )
        }?.let { return it }

        // Default fallback
        return FileFormat.UNKNOWN
    }

    /**
     * Infers a file format from a file path by examining the extension.
     *
     * @param path The file path
     * @return Inferred FileFormat or CSV as fallback
     */
    private fun inferFormatFromPath( path: String ): FileFormat
    {
        val extension = path.substringAfterLast( "." ).lowercase()
        return when ( extension )
        {
            "csv" -> FileFormat.CSV
            "json" -> FileFormat.JSON
            "parquet" -> FileFormat.PARQUET
            "avro" -> FileFormat.AVRO
            "xml" -> FileFormat.XML
            "xlsx", "xls" -> FileFormat.EXCEL
            "bin" -> FileFormat.BINARY
            "tsv" -> FileFormat.TSV
            "yaml", "yml" -> FileFormat.YAML
            "txt" -> FileFormat.TXT
            else -> FileFormat.UNKNOWN // Default for files with no/unknown extension
        }
    }

    private fun resolvePortId( id: String?, workflowNamespace: UUID?, kind: String ): UUID =
        id?.let { tryParseUuid( it ) }
            ?: workflowNamespace?.let { DeterministicUUID.v5( it, "port:$kind:${id ?: "unnamed"}" ) }
            ?: UUID.randomUUID()
}
