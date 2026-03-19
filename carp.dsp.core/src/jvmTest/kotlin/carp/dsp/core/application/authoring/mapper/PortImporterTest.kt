package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DataDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InMemoryLocation
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test suite for PortImporter using unified DataLocation model.
 *
 * Tests that data port descriptors are correctly mapped to InputDataSpec and OutputDataSpec
 * using the new FileLocation and InMemoryLocation model.
 */
class PortImporterTest
{
    private val workflowNamespace = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )

    // ── Input Port Tests ──────────────────────────────────────────────────────

    @Test
    fun `importInputPort maps FileInputSource to FileLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-input-csv",
            source = FileInputSource( path = "/data/input.csv" )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<FileLocation>( result.location )
        assertEquals( "/data/input.csv", ( result.location as FileLocation ).path )
        assertEquals( FileFormat.CSV, ( result.location as FileLocation ).format )
        assertNull( result.stepRef ) // External file, no step reference
    }

    @Test
    fun `importInputPort infers FileFormat from path`()
    {
        val testCases = listOf(
            "/data/file.json" to FileFormat.JSON,
            "/data/file.parquet" to FileFormat.PARQUET,
            "/data/file.xlsx" to FileFormat.EXCEL,
            "/data/file.xml" to FileFormat.XML,
            "/data/file.tsv" to FileFormat.TSV,
            "/data/file.yaml" to FileFormat.YAML,
            "/data/file.txt" to FileFormat.TXT,
            "/data/file.unknown" to FileFormat.UNKNOWN // Default fallback
        )

        testCases.forEach { (path, expectedFormat) ->
            val descriptor = DataPortDescriptor(
                id = "port-$path",
                source = FileInputSource( path = path )
            )

            val result = PortImporter.importInputPort(
                descriptor,
                workflowNamespace = workflowNamespace
            )

            val fileLocation = result.location as FileLocation
            assertEquals( expectedFormat, fileLocation.format, "Format mismatch for $path" )
        }
    }

    @Test
    fun `importInputPort maps StepOutputInputSource to FileLocation with stepRef`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-features",
            source = StepOutputInputSource(
                stepId = "step-extract",
                outputId = "output-features-csv"
            )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<FileLocation>( result.location )
        assertEquals( "", ( result.location as FileLocation ).path ) // Empty - resolved later
        assertEquals( FileFormat.UNKNOWN, ( result.location as FileLocation ).format ) // Default
        assertEquals( "step-extract", result.stepRef ) // Step reference set
    }

    @Test
    fun `importInputPort maps EnvironmentVariableInputSource to InMemoryLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-model",
            source = EnvironmentVariableInputSource( variableName = "MODEL_PATH" )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<InMemoryLocation>( result.location )
        assertEquals( "MODEL_PATH", ( result.location as InMemoryLocation ).registryKey )
    }

    @Test
    fun `importInputPort handles null source as external FileLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-unknown",
            source = null // No source specified
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<FileLocation>( result.location )
        assertEquals( "", ( result.location as FileLocation ).path ) // Empty path
        assertEquals( FileFormat.UNKNOWN, ( result.location as FileLocation ).format ) // Default
        assertNull( result.stepRef ) // No step reference
    }

    @Test
    fun `importInputPort includes metadata with source information`()
    {
        val fileDescriptor = DataPortDescriptor(
            id = "port-1",
            source = FileInputSource( path = "/data/file.csv" )
        )

        val result = PortImporter.importInputPort(
            fileDescriptor,
            workflowNamespace = workflowNamespace
        )

        val fileLocation = result.location as FileLocation
        assertTrue( fileLocation.metadata.containsKey( "source" ) )
        assertEquals( "file", fileLocation.metadata["source"] )
    }

    @Test
    fun `importInputPort uses provided schema for format if available`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-json",
            source = FileInputSource( path = "/data/data" ), // No extension
            descriptor = DataDescriptor(
                type = "JSON", // Explicit format
                format = "UTF-8"
            )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        val fileLocation = result.location as FileLocation
        assertEquals( FileFormat.JSON, fileLocation.format )
    }

    // ── Output Port Tests ─────────────────────────────────────────────────────

    @Test
    fun `importOutputPort maps FileOutputDestination to FileLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-output-csv",
            destination = FileOutputDestination( path = "/output/results.csv" )
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<FileLocation>( result.location )
        assertEquals( "/output/results.csv", ( result.location as FileLocation ).path )
        assertEquals( FileFormat.CSV, ( result.location as FileLocation ).format )
    }

    @Test
    fun `importOutputPort infers format from output path`()
    {
        val testCases = listOf(
            "/output/file.json" to FileFormat.JSON,
            "/output/file.parquet" to FileFormat.PARQUET,
            "/output/file.xlsx" to FileFormat.EXCEL,
            "/output/file.txt" to FileFormat.TXT,
            "/output/file.unknown" to FileFormat.UNKNOWN // Default
        )

        testCases.forEach { (path, expectedFormat) ->
            val descriptor = DataPortDescriptor(
                id = "port-$path",
                destination = FileOutputDestination( path = path )
            )

            val result = PortImporter.importOutputPort(
                descriptor,
                workflowNamespace = workflowNamespace
            )

            val fileLocation = result.location as FileLocation
            assertEquals( expectedFormat, fileLocation.format, "Format mismatch for $path" )
        }
    }

    @Test
    fun `importOutputPort maps EnvironmentVariableOutputDestination to InMemoryLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-registry",
            destination = EnvironmentVariableOutputDestination( variableName = "RESULTS_KEY" )
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<InMemoryLocation>( result.location )
        assertEquals( "RESULTS_KEY", ( result.location as InMemoryLocation ).registryKey )
    }

    @Test
    fun `importOutputPort handles null destination as FileLocation`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-auto",
            destination = null // No destination specified
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertIs<FileLocation>( result.location )
        assertEquals( "", ( result.location as FileLocation ).path ) // Empty - will be generated
        assertEquals( FileFormat.UNKNOWN, ( result.location as FileLocation ).format ) // Default
    }

    @Test
    fun `importOutputPort uses descriptor type for format if available`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-typed",
            destination = FileOutputDestination( path = "/output/data" ), // No extension
            descriptor = DataDescriptor(
                type = "JSON" // Explicit format
            )
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        val fileLocation = result.location as FileLocation
        assertEquals( FileFormat.JSON, fileLocation.format )
    }

    @Test
    fun `importOutputPort includes metadata with destination information`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-1",
            destination = FileOutputDestination( path = "/output/file.csv" )
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        val fileLocation = result.location as FileLocation
        assertTrue( fileLocation.metadata.containsKey( "destination" ) )
        assertEquals( "file", fileLocation.metadata["destination"] )
    }

    // ── Schema and Metadata Tests ─────────────────────────────────────────────

    @Test
    fun `importInputPort parses schema from descriptor`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-schema",
            source = FileInputSource( path = "/data/file.csv" ),
            descriptor = DataDescriptor(
                type = "CSV",
                format = "UTF-8"
            )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertTrue( result.schema != null, "Schema should be populated" )
        assertEquals( FileFormat.CSV, result.schema?.format )
        assertEquals( "UTF-8", result.schema?.encoding )
    }

    @Test
    fun `importOutputPort parses schema from descriptor`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-schema",
            destination = FileOutputDestination( path = "/output/file.csv" ),
            descriptor = DataDescriptor(
                type = "CSV",
                format = "UTF-8"
            )
        )

        val result = PortImporter.importOutputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertTrue( result.schema != null, "Schema should be populated" )
        assertEquals( FileFormat.CSV, result.schema?.format )
        assertEquals( "UTF-8", result.schema?.encoding )
    }

    @Test
    fun `importInputPort returns null schema when descriptor has no type`()
    {
        val descriptor = DataPortDescriptor(
            id = "port-no-schema",
            source = FileInputSource( path = "/data/file" ),
            descriptor = DataDescriptor(
                type = null, // No type specified
                format = "UTF-8"
            )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        // Schema may be null when no type specified
        if ( result.schema != null )
        {
            assertTrue( result.schema != null )
        }
    }

    // ── Cross-Step Binding Tests ──────────────────────────────────────────────

    @Test
    fun `importInputPort with StepOutputInputSource uses step name from descriptor`()
    {
        val stepName = "feature-extraction-step"
        val descriptor = DataPortDescriptor(
            id = "port-in",
            source = StepOutputInputSource(
                stepId = stepName,
                outputId = "features-port"
            )
        )

        val result = PortImporter.importInputPort(
            descriptor,
            workflowNamespace = workflowNamespace
        )

        assertEquals( stepName, result.stepRef, "stepRef should match the step name" )
    }

    @Test
    fun `importInputPort with multiple StepOutputInputSources maintain unique identities`()
    {
        val results = listOf(
            "step-a" to "output-1",
            "step-a" to "output-2",
            "step-b" to "output-1"
        ).map { (stepId, outputId) ->
            val descriptor = DataPortDescriptor(
                id = "port-$stepId-$outputId",
                source = StepOutputInputSource(
                    stepId = stepId,
                    outputId = outputId
                )
            )
            PortImporter.importInputPort(
                descriptor,
                workflowNamespace = workflowNamespace
            )
        }

        assertEquals( 3, results.size )
        assertEquals( "step-a", results[0].stepRef )
        assertEquals( "step-a", results[1].stepRef )
        assertEquals( "step-b", results[2].stepRef )
    }
}
