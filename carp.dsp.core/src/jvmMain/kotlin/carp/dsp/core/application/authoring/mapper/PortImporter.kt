package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import dk.cachet.carp.analytics.domain.data.DataSchema
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.common.application.UUID

/**
 * Maps `DataPortDescriptor` to `InputDataSpec` / `OutputDataSpec`.
 *
 */
internal object PortImporter
{
    private val ZERO_UUID: UUID = UUID.parse("00000000-0000-0000-0000-000000000000")

    /**
     * Maps a data port descriptor to an input data spec.
     *
     * ### Best-Effort Source Resolution
     *
     * If the descriptor specifies a source:
     * - [FileInputSource] → [FileSystemSource]
     * - [StepOutputInputSource] → [StepOutputSource] (uses placeholder UUID if step not found)
     * - [EnvironmentVariableInputSource] → [InMemorySource]
     *
     * If no source specified: uses R1 placeholder [StepOutputSource] with zero UUID.
     *
     * Validation of whether step references are valid is deferred to the linter.
     *
     * @param portDescriptor The input port descriptor
     * @param stepIdMap Mapping from semantic step IDs (strings) to UUIDs for resolution
     * @return Domain InputDataSpec with mapped source
     */
    fun importInputPort(
        portDescriptor: DataPortDescriptor,
        stepIdMap: Map<String, UUID>
    ): InputDataSpec
    {
        val portId = portDescriptor.id?.let { tryParseUuid( it ) } ?: UUID.randomUUID()

        val source = when ( val inputSource = portDescriptor.source )
        {
            is FileInputSource ->
                FileSystemSource(
                    path = inputSource.path,
                    format = inferFormatFromPath( inputSource.path ),
                    metadata = emptyMap()
                )

            is StepOutputInputSource ->
            {
                // Best-effort: try to resolve semantic step ID to UUID
                val stepUUID = stepIdMap[inputSource.stepId]
                    ?: UUID.randomUUID() // Placeholder if not found (linter will catch)

                StepOutputSource(
                    stepId = stepUUID,
                    outputId = tryParseUuid( inputSource.outputId ) ?: UUID.randomUUID(),
                    metadata = emptyMap()
                )
            }

            is EnvironmentVariableInputSource ->
                InMemorySource(
                    registryKey = inputSource.variableName,
                    metadata = emptyMap()
                )

            null ->
                // placeholder: no source specified
                StepOutputSource(
                    stepId = ZERO_UUID,
                    outputId = portId,
                    metadata = emptyMap()
                )
        }

        return InputDataSpec(
            id = portId,
            name = portId.toString(),
            description = null,
            schema = importSchema( portDescriptor ),
            source = source,
            required = true,
            constraints = null
        )
    }

    /**
     * Maps a data port descriptor to an output data spec.
     *
     * ### Destination Mapping
     *
     * If the descriptor specifies a destination:
     * - [FileOutputDestination] → [FileDestination]
     * - [EnvironmentVariableOutputDestination] → [RegistryDestination]
     *
     * If no destination specified: uses placeholder [FileDestination] with empty path.
     *
     * @param portDescriptor The output port descriptor
     * @return Domain OutputDataSpec with mapped destination
     */
    fun importOutputPort(portDescriptor: DataPortDescriptor ): OutputDataSpec
    {
        val portId = portDescriptor.id?.let { tryParseUuid( it ) } ?: UUID.randomUUID()

        val destination = when ( val outputDest = portDescriptor.destination )
        {
            is FileOutputDestination ->
                FileDestination(
                    path = outputDest.path,
                    format = inferFormatFromPath( outputDest.path ),
                    overwrite = false,
                    writeMode = dk.cachet.carp.analytics.domain.data.WriteMode.ERROR_IF_EXISTS
                )

            is EnvironmentVariableOutputDestination ->
                RegistryDestination(
                    key = outputDest.variableName,
                    overwrite = true
                )

            null ->
                // placeholder: no destination specified
                FileDestination(
                    path = "",
                    format = portDescriptor.descriptor?.type?.let { parseFileFormat( it ) } ?: FileFormat.CSV,
                    overwrite = false,
                    writeMode = dk.cachet.carp.analytics.domain.data.WriteMode.ERROR_IF_EXISTS
                )
        }

        return OutputDataSpec(
            id = portId,
            name = portId.toString(),
            description = null,
            schema = importSchema( portDescriptor ),
            destination = destination,
            format = null
        )
    }

    /**
     * Maps a data descriptor to a data schema.
     *
     * @param descriptor The data port descriptor
     * @return Domain DataSchema or null if type not specified
     */
    private fun importSchema(descriptor: DataPortDescriptor ): DataSchema? =
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
     * @param type The type string (case-insensitive)
     * @return Matched FileFormat or FileFormat.CSV as fallback
     */
    private fun parseFileFormat( type: String ): FileFormat =
        FileFormat.entries.firstOrNull { it.name.equals( type, ignoreCase = true ) }
            ?: FileFormat.CSV

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
            else -> FileFormat.CSV // Default fallback
        }
    }
}
