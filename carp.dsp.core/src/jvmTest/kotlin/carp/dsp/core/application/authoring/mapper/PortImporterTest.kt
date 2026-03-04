package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DataDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortImporterTest
{
    private val emptyStepMap: Map<String, UUID> = emptyMap()

    // ── importInputPort: id ───────────────────────────────────────────────────

    @Test
    fun `importInputPort preserves explicit UUID id`()
    {
        val portId = UUID.randomUUID()
        val d = DataPortDescriptor( id = portId.toString() )

        assertEquals( portId, PortImporter.importInputPort( d, emptyStepMap ).id )
    }

    @Test
    fun `importInputPort generates UUID when id is null`()
    {
        val d = DataPortDescriptor( id = null )
        assertNotNull( PortImporter.importInputPort( d, emptyStepMap ).id )
    }

    @Test
    fun `importInputPort sets name to portId toString`()
    {
        val portId = UUID.randomUUID()
        val result = PortImporter.importInputPort( DataPortDescriptor( id = portId.toString() ), emptyStepMap )
        assertEquals( portId.toString(), result.name )
    }

    // ── importInputPort: source = null (R1 placeholder) ───────────────────────

    @Test
    fun `importInputPort null source produces StepOutputSource placeholder`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), source = null )
        assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
    }

    @Test
    fun `importInputPort null source placeholder stepId is zero UUID`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), source = null )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( UUID.parse("00000000-0000-0000-0000-000000000000"), source.stepId )
    }

    @Test
    fun `importInputPort null source placeholder outputId matches portId`()
    {
        val portId = UUID.randomUUID()
        val d = DataPortDescriptor( id = portId.toString(), source = null )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( portId, source.outputId )
    }

    @Test
    fun `importInputPort null source placeholder metadata is empty`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), source = null )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( emptyMap(), source.metadata )
    }

    // ── importInputPort: FileInputSource ──────────────────────────────────────

    @Test
    fun `importInputPort FileInputSource maps to FileSystemSource`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/raw.csv") )
        assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
    }

    @Test
    fun `importInputPort FileInputSource path is preserved`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/raw.csv") )
        val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( "./data/raw.csv", source.path )
    }

    @Test
    fun `importInputPort FileInputSource infers CSV format from extension`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/raw.csv") )
        val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( FileFormat.CSV, source.format )
    }

    @Test
    fun `importInputPort FileInputSource infers parquet format from extension`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/features.parquet") )
        val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( FileFormat.PARQUET, source.format )
    }

    @Test
    fun `importInputPort FileInputSource infers JSON format from extension`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/config.json") )
        val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( FileFormat.JSON, source.format )
    }

    @Test
    fun `importInputPort FileInputSource unknown extension falls back to CSV`()
    {
        val d = DataPortDescriptor( source = FileInputSource("./data/raw.edf") )
        val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( FileFormat.CSV, source.format )
    }

    // ── importInputPort: StepOutputInputSource ────────────────────────────────

    @Test
    fun `importInputPort StepOutputInputSource maps to StepOutputSource`()
    {
        val d = DataPortDescriptor( source = StepOutputInputSource("step-1", UUID.randomUUID().toString()) )
        assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
    }

    @Test
    fun `importInputPort StepOutputInputSource resolves stepId from stepIdMap`()
    {
        val stepUUID = UUID.randomUUID()
        val stepMap = mapOf("step-1" to stepUUID)
        val d = DataPortDescriptor( source = StepOutputInputSource("step-1", UUID.randomUUID().toString()) )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, stepMap ).source )
        assertEquals( stepUUID, source.stepId )
    }

    @Test
    fun `importInputPort StepOutputInputSource generates placeholder stepId when not in map`()
    {
        val d = DataPortDescriptor( source = StepOutputInputSource("unknown-step", UUID.randomUUID().toString()) )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertNotNull( source.stepId )
    }

    @Test
    fun `importInputPort StepOutputInputSource parses outputId as UUID`()
    {
        val outputUUID = UUID.randomUUID()
        val d = DataPortDescriptor( source = StepOutputInputSource("step-1", outputUUID.toString()) )
        val source = assertIs<StepOutputSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( outputUUID, source.outputId )
    }

    // ── importInputPort: EnvironmentVariableInputSource ───────────────────────

    @Test
    fun `importInputPort EnvironmentVariableInputSource maps to InMemorySource`()
    {
        val d = DataPortDescriptor( source = EnvironmentVariableInputSource("MY_VAR") )
        assertIs<InMemorySource>( PortImporter.importInputPort( d, emptyStepMap ).source )
    }

    @Test
    fun `importInputPort EnvironmentVariableInputSource preserves variableName as registryKey`()
    {
        val d = DataPortDescriptor( source = EnvironmentVariableInputSource("EEG_DATA_PATH") )
        val source = assertIs<InMemorySource>( PortImporter.importInputPort( d, emptyStepMap ).source )
        assertEquals( "EEG_DATA_PATH", source.registryKey )
    }

    // ── importInputPort: schema ───────────────────────────────────────────────

    @Test
    fun `importInputPort schema is null when descriptor is null`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), descriptor = null )
        assertNull( PortImporter.importInputPort( d, emptyStepMap ).schema )
    }

    @Test
    fun `importInputPort schema is null when descriptor type is null`()
    {
        val d = DataPortDescriptor(
            id = UUID.randomUUID().toString(),
            descriptor = DataDescriptor( type = null, format = "UTF-8" )
        )
        assertNull( PortImporter.importInputPort( d, emptyStepMap ).schema )
    }

    @Test
    fun `importInputPort schema format is parsed from descriptor type`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "csv" ) )
        assertEquals( FileFormat.CSV, PortImporter.importInputPort( d, emptyStepMap ).schema?.format )
    }

    @Test
    fun `importInputPort schema encoding is taken from descriptor format`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "csv", format = "UTF-16" ) )
        assertEquals( "UTF-16", PortImporter.importInputPort( d, emptyStepMap ).schema?.encoding )
    }

    @Test
    fun `importInputPort schema encoding defaults to UTF-8 when format is null`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "csv", format = null ) )
        assertEquals( "UTF-8", PortImporter.importInputPort( d, emptyStepMap ).schema?.encoding )
    }

    // ── importOutputPort: id ──────────────────────────────────────────────────

    @Test
    fun `importOutputPort preserves explicit UUID id`()
    {
        val portId = UUID.randomUUID()
        assertEquals( portId, PortImporter.importOutputPort( DataPortDescriptor( id = portId.toString() ) ).id )
    }

    @Test
    fun `importOutputPort generates UUID when id is null`()
    {
        assertNotNull( PortImporter.importOutputPort( DataPortDescriptor( id = null ) ).id )
    }

    @Test
    fun `importOutputPort sets name to portId toString`()
    {
        val portId = UUID.randomUUID()
        val result = PortImporter.importOutputPort( DataPortDescriptor( id = portId.toString() ) )
        assertEquals( portId.toString(), result.name )
    }

    // ── importOutputPort: destination = null (R1 placeholder) ────────────────

    @Test
    fun `importOutputPort null destination produces FileDestination placeholder`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), destination = null )
        assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
    }

    @Test
    fun `importOutputPort null destination placeholder path is empty`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), destination = null )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( "", dest.path )
    }

    @Test
    fun `importOutputPort null destination format derived from descriptor type`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "parquet" ), destination = null )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.PARQUET, dest.format )
    }

    @Test
    fun `importOutputPort null destination falls back to CSV when descriptor is null`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), descriptor = null, destination = null )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.CSV, dest.format )
    }

    @Test
    fun `importOutputPort null destination falls back to CSV when descriptor type is null`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = null ), destination = null )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.CSV, dest.format )
    }

    // ── importOutputPort: FileOutputDestination ───────────────────────────────

    @Test
    fun `importOutputPort FileOutputDestination maps to FileDestination`()
    {
        val d = DataPortDescriptor( destination = FileOutputDestination("./out/results.csv") )
        assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
    }

    @Test
    fun `importOutputPort FileOutputDestination path is preserved`()
    {
        val d = DataPortDescriptor( destination = FileOutputDestination("./out/results.csv") )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( "./out/results.csv", dest.path )
    }

    @Test
    fun `importOutputPort FileOutputDestination infers CSV format from extension`()
    {
        val d = DataPortDescriptor( destination = FileOutputDestination("./out.csv") )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.CSV, dest.format )
    }

    @Test
    fun `importOutputPort FileOutputDestination infers parquet format from extension`()
    {
        val d = DataPortDescriptor( destination = FileOutputDestination("./out.parquet") )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.PARQUET, dest.format )
    }

    @Test
    fun `importOutputPort FileOutputDestination unknown extension falls back to CSV`()
    {
        val d = DataPortDescriptor( destination = FileOutputDestination("./out.edf") )
        val dest = assertIs<FileDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( FileFormat.CSV, dest.format )
    }

    // ── importOutputPort: EnvironmentVariableOutputDestination ────────────────

    @Test
    fun `importOutputPort EnvironmentVariableOutputDestination maps to RegistryDestination`()
    {
        val d = DataPortDescriptor( destination = EnvironmentVariableOutputDestination("OUT_PATH") )
        assertIs<RegistryDestination>( PortImporter.importOutputPort( d ).destination )
    }

    @Test
    fun `importOutputPort EnvironmentVariableOutputDestination preserves variableName as key`()
    {
        val d = DataPortDescriptor( destination = EnvironmentVariableOutputDestination("CLEAN_EEG_PATH") )
        val dest = assertIs<RegistryDestination>( PortImporter.importOutputPort( d ).destination )
        assertEquals( "CLEAN_EEG_PATH", dest.key )
    }

    // ── importOutputPort: schema ──────────────────────────────────────────────

    @Test
    fun `importOutputPort schema is null when descriptor is null`()
    {
        val d = DataPortDescriptor( id = UUID.randomUUID().toString(), descriptor = null )
        assertNull( PortImporter.importOutputPort( d ).schema )
    }

    @Test
    fun `importOutputPort schema format is parsed from descriptor type`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "json" ) )
        assertEquals( FileFormat.JSON, PortImporter.importOutputPort( d ).schema?.format )
    }

    @Test
    fun `importOutputPort schema encoding defaults to UTF-8 when format is null`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "json", format = null ) )
        assertEquals( "UTF-8", PortImporter.importOutputPort( d ).schema?.encoding )
    }

    // ── parseFileFormat: case-insensitivity and all known values ─────────────

    @Test
    fun `type matching is case-insensitive for uppercase`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "CSV" ) )
        assertEquals( FileFormat.CSV, PortImporter.importOutputPort( d ).schema?.format )
    }

    @Test
    fun `type matching is case-insensitive for mixed case`()
    {
        val d = DataPortDescriptor( descriptor = DataDescriptor( type = "Json" ) )
        assertEquals( FileFormat.JSON, PortImporter.importOutputPort( d ).schema?.format )
    }

    @Test
    fun `correctly maps all known FileFormat type strings`()
    {
        val cases = mapOf(
            "csv" to FileFormat.CSV,
            "json" to FileFormat.JSON,
            "parquet" to FileFormat.PARQUET,
            "avro" to FileFormat.AVRO,
            "xml" to FileFormat.XML,
            "excel" to FileFormat.EXCEL,
            "binary" to FileFormat.BINARY,
            "tsv" to FileFormat.TSV,
            "yaml" to FileFormat.YAML,
        )
        cases.forEach { ( typeStr, expected ) ->
            val d = DataPortDescriptor( descriptor = DataDescriptor( type = typeStr ) )
            assertEquals(
                expected,
                PortImporter.importOutputPort( d ).schema?.format,
                "Expected $expected for type string '$typeStr'"
            )
        }
    }

    // ── inferFormatFromPath: all extensions ───────────────────────────────────

    @Test
    fun `inferFormatFromPath correctly maps all known extensions via FileInputSource`()
    {
        val cases = mapOf(
            "data.csv" to FileFormat.CSV,
            "data.json" to FileFormat.JSON,
            "data.parquet" to FileFormat.PARQUET,
            "data.avro" to FileFormat.AVRO,
            "data.xml" to FileFormat.XML,
            "data.xlsx" to FileFormat.EXCEL,
            "data.xls" to FileFormat.EXCEL,
            "data.bin" to FileFormat.BINARY,
            "data.tsv" to FileFormat.TSV,
            "data.yaml" to FileFormat.YAML,
            "data.yml" to FileFormat.YAML,
        )
        cases.forEach { ( path, expected ) ->
            val d = DataPortDescriptor( source = FileInputSource( path ) )
            val source = assertIs<FileSystemSource>( PortImporter.importInputPort( d, emptyStepMap ).source )
            assertEquals( expected, source.format, "Expected $expected for path '$path'" )
        }
    }
}
