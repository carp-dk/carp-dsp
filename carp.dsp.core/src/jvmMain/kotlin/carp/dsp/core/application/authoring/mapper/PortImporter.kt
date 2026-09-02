 package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.ExternalInputSource
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.ProtocolInputSource
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
 * - ProtocolInputSource → FileLocation (empty path) + protocol provenance metadata
 * - ExternalInputSource → FileLocation (uri as path) + external provenance metadata
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
        val declaredFormat = portDescriptor.descriptor?.fileFormat?.let { parseFileFormat( it ) }

        return when ( val inputSource = portDescriptor.source )
        {
            is FileInputSource -> fileLocationFor( inputSource, declaredFormat ) to null
            is StepOutputInputSource -> stepOutputLocation( inputSource.outputId ) to inputSource.stepId
            is EnvironmentVariableInputSource -> environmentLocationFor( inputSource ) to null
            is ProtocolInputSource -> protocolLocationFor( inputSource, declaredFormat ) to null
            is ExternalInputSource -> externalLocationFor( inputSource, declaredFormat ) to null
            null -> unresolvedLocation() to null
        }
    }

    /** [FileInputSource]: a concrete path; format falls back to the file extension. */
    private fun fileLocationFor( source: FileInputSource, declaredFormat: FileFormat? ) =
        FileLocation(
            path = source.path,
            format = declaredFormat ?: inferFormatFromPath( source.path ),
            metadata = mapOf( "source" to "file" )
        )

    /**
     * [StepOutputInputSource]: path and format are resolved later by BindingsResolver.
     * The producer output port id is carried in metadata as `outputId` so the
     * binding wires to that specific output rather than matching on port name -
     * which lets a step consume an upstream output whose name differs from its own
     * input port (as generic library steps require).
     */
    private fun stepOutputLocation( outputId: String ) =
        FileLocation(
            path = "",
            format = FileFormat.UNKNOWN,
            metadata = mapOf( "source" to "step-output", "outputId" to outputId )
        )

    /** [EnvironmentVariableInputSource]: a registry key rather than a file. */
    private fun environmentLocationFor( source: EnvironmentVariableInputSource ) =
        InMemoryLocation(
            registryKey = source.variableName,
            metadata = mapOf( "source" to "environment" )
        )

    /**
     * [ProtocolInputSource]: data collected by a study protocol. The path is resolved at
     * execution time; the protocol reference and expected CARP data type are carried in
     * metadata so the planner can validate the binding (F5).
     */
    private fun protocolLocationFor( source: ProtocolInputSource, declaredFormat: FileFormat? ) =
        FileLocation(
            path = "",
            format = declaredFormat ?: FileFormat.UNKNOWN,
            metadata = buildMap {
                put( "source", "protocol" )
                put( "protocolId", source.protocol.id )
                put( "dataType", source.dataType )
                source.protocol.version?.let { put( "protocolVersion", it.toString() ) }
                source.protocol.name?.let { put( "protocolName", it ) }
            }
        )

    /**
     * [ExternalInputSource]: open or externally supplied data. The uri doubles as the path
     * and as a format hint; attribution is carried in metadata.
     */
    private fun externalLocationFor( source: ExternalInputSource, declaredFormat: FileFormat? ) =
        FileLocation(
            path = source.uri ?: "",
            format = declaredFormat
                ?: source.uri?.let { inferFormatFromPath( it ) }
                ?: FileFormat.UNKNOWN,
            metadata = buildMap {
                put( "source", "external" )
                source.uri?.let { put( "uri", it ) }
                source.citation?.let { put( "citation", it ) }
            }
        )

    /** No source declared: an empty placeholder resolved later. */
    private fun unresolvedLocation() =
        FileLocation( path = "", format = FileFormat.UNKNOWN, metadata = emptyMap() )

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
                val fileFormat = portDescriptor.descriptor?.fileFormat?.let { parseFileFormat( it ) }
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
                    format = portDescriptor.descriptor?.fileFormat?.let { parseFileFormat( it ) }
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
            if ( it.fileFormat == null ) null
            else DataSchema(
                format = parseFileFormat( it.fileFormat ),
                encoding = it.encoding ?: "UTF-8",
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
