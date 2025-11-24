package carp.dsp.core.application.process

import carp.dsp.core.application.environment.CondaEnvironment
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.application.data.InMemoryData
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import dk.cachet.carp.analytics.domain.data.ICarpTabularData
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mock implementation of ICarpTabularData for testing purposes.
 */
private data class MockTabularData(val content: String) : ICarpTabularData {
    override fun toString(): String = content
}

class PythonProcessTest {

    private lateinit var tempScriptPath: Path
    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        // Create temporary directory and script for testing
        tempDir = Files.createTempDirectory("python-process-test")
        tempScriptPath = tempDir.resolve("test_script.py")
        tempScriptPath.writeText(
            """
            import sys
            import argparse
            
            parser = argparse.ArgumentParser()
            parser.add_argument('--input', nargs='*', default=[])
            parser.add_argument('--output', default=None)
            args = parser.parse_args()
            
            print("Script executed successfully")
        """.trimIndent()
        )

        // Disable script path validation for tests
        PythonProcess.validateScriptPath = false
    }

    @After
    fun tearDown() {
        // Clean up temporary files
        tempScriptPath.toFile().delete()
        tempDir.toFile().deleteRecursively()

        // Re-enable script path validation
        PythonProcess.validateScriptPath = true
    }

    @Test
    fun testDefaultConstruction() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        assertEquals("TestProcess", process.name)
        assertEquals("", process.description)
        assertEquals(tempScriptPath.toString(), process.scriptPath)
        assertTrue(process.arguments.isEmpty())
        assertEquals("python", process.pythonExecutable)
        assertTrue(process.useCondaRun)
    }

    @Test
    fun testConstructionWithAllParameters() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val args = listOf("--arg1", "value1", "--arg2", "value2")

        val process = PythonProcess(
            name = "TestProcess",
            description = "A test process",
            executionContext = context,
            scriptPath = tempScriptPath.toString(),
            arguments = args,
            pythonExecutable = "python3",
            useCondaRun = false
        )

        assertEquals("TestProcess", process.name)
        assertEquals("A test process", process.description)
        assertEquals(tempScriptPath.toString(), process.scriptPath)
        assertEquals(args, process.arguments)
        assertEquals("python3", process.pythonExecutable)
        assertFalse(process.useCondaRun)
    }

    @Test
    fun testScriptPathValidation_EmptyPath() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        assertFailsWith<IllegalArgumentException> {
            PythonProcess(
                name = "TestProcess",
                executionContext = context,
                scriptPath = ""
            )
        }
    }

    @Test
    fun testScriptPathValidation_BlankPath() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        assertFailsWith<IllegalArgumentException> {
            PythonProcess(
                name = "TestProcess",
                executionContext = context,
                scriptPath = "   "
            )
        }
    }

    @Test
    fun testArgumentsValidation_BlankArgument() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        assertFailsWith<IllegalArgumentException> {
            PythonProcess(
                name = "TestProcess",
                executionContext = context,
                scriptPath = tempScriptPath.toString(),
                arguments = listOf("--arg1", "", "--arg2")
            )
        }
    }

    @Test
    fun testScriptPathValidation_NonExistentFile() {
        PythonProcess.validateScriptPath = true
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        assertFailsWith<IllegalArgumentException> {
            PythonProcess(
                name = "TestProcess",
                executionContext = context,
                scriptPath = "/non/existent/script.py"
            )
        }
    }

    @Test
    fun testGetFormattedCommand_WithCondaRun() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            arguments = listOf("--arg1", "value1"),
            useCondaRun = true
        )

        val command = process.getFormattedCommand()
        assertEquals("conda run -n test-env python /path/to/script.py --arg1 value1", command)
    }

    @Test
    fun testGetFormattedCommand_WithoutCondaRun() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            arguments = listOf("--arg1", "value1"),
            useCondaRun = false
        )

        val command = process.getFormattedCommand()
        assertEquals("python /path/to/script.py --arg1 value1", command)
    }

    @Test
    fun testGetFormattedCommand_NoArguments() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            useCondaRun = false
        )

        val command = process.getFormattedCommand()
        assertEquals("python /path/to/script.py", command)
    }

    @Test
    fun testGetFormattedCommand_CustomPythonExecutable() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            pythonExecutable = "python3.11",
            useCondaRun = true
        )

        val command = process.getFormattedCommand()
        assertEquals("conda run -n test-env python3.11 /path/to/script.py", command)
    }

    @Test
    fun testGetFormattedCommand_WithoutEnvironment() {
        val context = ExecutionContext(environment = null)
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            useCondaRun = true
        )

        assertFailsWith<IllegalStateException> {
            process.getFormattedCommand()
        }
    }

    @Test
    fun testGetArguments() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = "/path/to/script.py",
            arguments = listOf("--arg1", "value1")
        )

        val args = process.getArguments() as Map<*, *>
        assertEquals("/path/to/script.py", args["script"])
        assertEquals(listOf("--arg1", "value1"), args["arguments"])
        assertEquals("", args["stdin"])
        assertEquals("python", args["pythonExecutable"])
        assertEquals(true, args["useCondaRun"])
    }

    @Test
    fun testResolveBindings_FileSystemInput() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val inputs = listOf(
            InputDataSpec("test input", "test input", source = FileSystemSource("/path/to/input.csv", FileFormat.CSV))
        )
        val dataRegistry = DataRegistry()

        process.resolveBindings(inputs, emptyList(), dataRegistry)

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--input /path/to/input.csv"))
    }

    @Test
    fun testResolveBindings_MultipleFileSystemInputs() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val inputs = listOf(
            InputDataSpec("test input 1", "test input 1", source = FileSystemSource("/path/to/input1.csv", FileFormat.CSV)),
            InputDataSpec("test input 2", "test input 2", source = FileSystemSource("/path/to/input2.csv", FileFormat.CSV))
        )
        val dataRegistry = DataRegistry()

        process.resolveBindings(inputs, emptyList(), dataRegistry)

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--input /path/to/input1.csv"))
        assertTrue(command.contains("--input /path/to/input2.csv"))
    }

    @Test
    fun testResolveBindings_FileDestination() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val outputs = listOf(
            OutputDataSpec("test output", "test output", destination = FileDestination("/path/to/output.csv", FileFormat.CSV))
        )
        val dataRegistry = DataRegistry()

        process.resolveBindings(emptyList(), outputs, dataRegistry)

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--output /path/to/output.csv"))
    }

    @Test
    fun testResolveBindings_RegistryDestination() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val outputs = listOf(
            OutputDataSpec("output data", "output data", destination = RegistryDestination("output-key"))
        )
        val dataRegistry = DataRegistry()

        process.resolveBindings(emptyList(), outputs, dataRegistry)

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--output -"))
    }

    @Test
    fun testResolveBindings_InMemoryInput() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val dataRegistry = DataRegistry()
        val testData = MockTabularData("test data content")
        val inMemoryData = InMemoryData(testData)
        dataRegistry.register("test-key", inMemoryData)

        val inputs = listOf(
            InputDataSpec("test input", "test input", source = InMemorySource("test-key"))
        )

        process.resolveBindings(inputs, emptyList(), dataRegistry)

        val stdinBuffer = process.getStdinBuffer()
        assertTrue(stdinBuffer.contains("test data content"))
    }

    @Test
    fun testResolveBindings_MultipleInMemoryInputs() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val dataRegistry = DataRegistry()
        dataRegistry.register("key1", InMemoryData(MockTabularData("data1")))
        dataRegistry.register("key2", InMemoryData(MockTabularData("data2")))

        val inputs = listOf(
            InputDataSpec("test input 1", "test input 1", source = InMemorySource("key1")),
            InputDataSpec("test input 2", "test input 2", source = InMemorySource("key2"))
        )

        process.resolveBindings(inputs, emptyList(), dataRegistry)

        val stdinBuffer = process.getStdinBuffer()
        assertTrue(stdinBuffer.contains("data1"))
        assertTrue(stdinBuffer.contains("data2"))
    }

    @Test
    fun testResolveBindings_MixedInputsAndOutputs() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val dataRegistry = DataRegistry()
        dataRegistry.register("mem-key", InMemoryData(MockTabularData("memory data")))

        val inputs = listOf(
            InputDataSpec("file input", "file input", source = FileSystemSource("/path/to/input.csv", FileFormat.CSV)),
            InputDataSpec("memory input", "memory input", source = InMemorySource("mem-key"))
        )

        val outputs = listOf(
            OutputDataSpec("test output", "test output", destination = FileDestination("/path/to/output.csv", FileFormat.CSV)),
            OutputDataSpec("result output", "result output", destination = RegistryDestination("result-key"))
        )

        process.resolveBindings(inputs, outputs, dataRegistry)

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--input /path/to/input.csv"))
        assertTrue(command.contains("--output /path/to/output.csv"))
        assertTrue(command.contains("--output -"))

        val stdinBuffer = process.getStdinBuffer()
        assertTrue(stdinBuffer.contains("memory data"))
    }

    @Test
    fun testResolveBindings_ClearsPreviousBindings() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val dataRegistry = DataRegistry()
        dataRegistry.register("key1", InMemoryData(MockTabularData("data1")))

        // First binding
        val inputs1 = listOf(InputDataSpec("test input 1", "test input 1", source = InMemorySource("key1")))
        process.resolveBindings(inputs1, emptyList(), dataRegistry)
        assertTrue(process.getStdinBuffer().contains("data1"))

        // Second binding should clear the first
        dataRegistry.register("key2", InMemoryData(MockTabularData("data2")))
        val inputs2 = listOf(InputDataSpec("test input 2", "test input 2", source = InMemorySource("key2")))
        process.resolveBindings(inputs2, emptyList(), dataRegistry)

        val stdinBuffer = process.getStdinBuffer()
        assertFalse(stdinBuffer.contains("data1"))
        assertTrue(stdinBuffer.contains("data2"))
    }

    @Test
    fun testResolveBindings_InMemoryDataNotFound() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        val dataRegistry = DataRegistry()
        val inputs = listOf(
            InputDataSpec("test input", "test input", source = InMemorySource("non-existent-key"))
        )

        assertFailsWith<IllegalArgumentException> {
            process.resolveBindings(inputs, emptyList(), dataRegistry)
        }
    }

    @Test
    fun testBuilderPattern_MinimalConfig() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        val process = PythonProcess.builder("TestProcess")
            .executionContext(context)
            .scriptPath(tempScriptPath.toString())
            .build()

        assertEquals("TestProcess", process.name)
        assertEquals("", process.description)
        assertEquals(tempScriptPath.toString(), process.scriptPath)
        assertTrue(process.arguments.isEmpty())
    }

    @Test
    fun testBuilderPattern_FullConfig() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        val process = PythonProcess.builder("TestProcess")
            .description("Test description")
            .executionContext(context)
            .scriptPath(tempScriptPath.toString())
            .arguments("--arg1", "value1", "--arg2", "value2")
            .pythonExecutable("python3.11")
            .useCondaRun(false)
            .build()

        assertEquals("TestProcess", process.name)
        assertEquals("Test description", process.description)
        assertEquals(tempScriptPath.toString(), process.scriptPath)
        assertEquals(listOf("--arg1", "value1", "--arg2", "value2"), process.arguments)
        assertEquals("python3.11", process.pythonExecutable)
        assertFalse(process.useCondaRun)
    }

    @Test
    fun testBuilderPattern_MissingScriptPath() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))

        val builder = PythonProcess.builder("TestProcess")
            .executionContext(context)

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun testBuilderPattern_MissingExecutionContext() {
        val builder = PythonProcess.builder("TestProcess")
            .scriptPath(tempScriptPath.toString())

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun testGetStdinBuffer_Empty() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString()
        )

        assertEquals("", process.getStdinBuffer())
    }

    @Test
    fun testFormattedCommand_WithDynamicArguments() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString(),
            arguments = listOf("--static-arg", "value")
        )

        // Add dynamic arguments via binding resolution
        val outputs = listOf(
            OutputDataSpec("test output", "test output", destination = FileDestination("/path/to/output.csv", FileFormat.CSV))
        )
        process.resolveBindings(emptyList(), outputs, DataRegistry())

        val command = process.getFormattedCommand()
        assertTrue(command.contains("--static-arg value"))
        assertTrue(command.contains("--output /path/to/output.csv"))
    }

    @Test
    fun testGetArguments_WithDynamicBindings() {
        val context = ExecutionContext(environment = CondaEnvironment("test-env"))
        val process = PythonProcess(
            name = "TestProcess",
            executionContext = context,
            scriptPath = tempScriptPath.toString(),
            arguments = listOf("--arg1", "value1")
        )

        val dataRegistry = DataRegistry()
        dataRegistry.register("key1", InMemoryData(MockTabularData("test data")))

        val inputs = listOf(InputDataSpec("test input", "test input", source = InMemorySource("key1")))
        val outputs =
            listOf(OutputDataSpec("test output", "test output", destination = FileDestination("/out.csv", FileFormat.CSV)))

        process.resolveBindings(inputs, outputs, dataRegistry)

        val args = process.getArguments() as Map<*, *>
        val argsList = args["arguments"] as List<*>

        assertTrue(argsList.contains("--arg1"))
        assertTrue(argsList.contains("value1"))
        assertTrue(argsList.contains("--output"))
        assertTrue(argsList.contains("/out.csv"))
        assertEquals("test data", args["stdin"])
    }
}

