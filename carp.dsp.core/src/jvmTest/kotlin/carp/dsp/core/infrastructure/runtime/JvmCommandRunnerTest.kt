package carp.dsp.core.infrastructure.runtime

import dk.cachet.carp.analytics.application.runtime.Command
import kotlin.io.path.ExperimentalPathApi
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class JvmCommandRunnerTest {

    private lateinit var runner: JvmCommandRunner
    private lateinit var tempDir: Path
    private var compiledDir: Path? = null

    @BeforeTest
    fun setUp() {
        runner = JvmCommandRunner()
        tempDir = createTempDirectory("jvm-command-runner-test")
    }

    @AfterTest
    fun tearDown() {
        if (::tempDir.isInitialized) {
            tempDir.deleteRecursively()
        }
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val javaBin = if (isWindows) "java.exe" else "java"
        return Paths.get(javaHome, "bin", javaBin).toString()
    }

    private fun cwdWithSpaces(): String {
        val cwd = tempDir.resolve("cwd with spaces")
        cwd.createDirectories()
        return cwd.toString()
    }

    private fun ensureHelperCompiled(): Path {
        compiledDir?.let { return it }
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue("JDK is required to compile helper program", compiler != null)

        val outputDir = tempDir.resolve("compiled")
        outputDir.createDirectories()
        val sourceFile = tempDir.resolve("RunnerTestMain.java")
        Files.newBufferedWriter(sourceFile).use { writer ->
            writer.write(helperSource())
        }

        val fileManager = compiler!!.getStandardFileManager(null, null, null)
        val diagnostics = compiler.getTask(
            null,
            fileManager,
            null,
            listOf("-d", outputDir.toString()),
            null,
            fileManager.getJavaFileObjectsFromFiles(listOf(File(sourceFile.toString())))
        )
        assumeTrue("Helper program compilation failed", diagnostics.call())
        compiledDir = outputDir
        return outputDir
    }

    private fun helperSource(): String = """
        public class RunnerTestMain {
            public static void main(String[] args) throws Exception {
                if (args.length == 0) { System.out.println("no args"); return; }
                switch (args[0]) {
                    case "printOutErr":
                        System.out.println("OUT");
                        System.err.println("ERR");
                        return;
                    case "exitCode":
                        int code = Integer.parseInt(args[1]);
                        System.exit(code);
                        return;
                    case "echoStdin":
                        byte[] data = System.in.readAllBytes();
                        System.out.write(data);
                        return;
                    case "env":
                        String val = System.getenv(args[1]);
                        System.out.print(val == null ? "" : val);
                        return;
                    case "sleep":
                        long ms = Long.parseLong(args[1]);
                        Thread.sleep(ms);
                        System.out.println("DONE");
                        return;
                    case "spam":
                        int kb = Integer.parseInt(args[1]);
                        byte[] buf = new byte[1024];
                        for (int i = 0; i < buf.length; i++) buf[i] = 'A';
                        for (int i = 0; i < kb; i++) {
                            System.out.write(buf);
                        }
                        return;
                    default:
                        System.out.println("unknown mode");
                }
            }
        }
    """

    private fun helperCommand(vararg args: String, cwd: String? = null, env: Map<String, String> = emptyMap(), timeoutMs: Long? = 10_000): Command {
        val outputDir = ensureHelperCompiled()
        val baseArgs = listOf("-cp", outputDir.toString(), "RunnerTestMain") + args.toList()
        return Command(
            exe = javaExecutable(),
            args = baseArgs,
            cwd = cwd,
            env = env,
            stdin = null,
            timeoutMs = timeoutMs
        )
    }

    @Test
    fun trivial_command_runs_successfully() {
        val command = Command(
            exe = javaExecutable(),
            args = listOf("-version"),
            cwd = null,
            env = emptyMap(),
            stdin = null,
            timeoutMs = 10_000
        )

        val result = runner.run(command)

        assertEquals(0, result.exitCode)
        assertTrue(result.durationMs >= 0)
        assertTrue(result.stdout.isNotEmpty() || result.stderr.isNotEmpty())
        assertFalse(result.timedOut)
    }

    @Test
    fun args_with_spaces_and_cwd_with_spaces_work() {
        val command = Command(
            exe = javaExecutable(),
            args = listOf("-Dcarp.test=hello world", "-version"),
            cwd = cwdWithSpaces(),
            env = emptyMap(),
            stdin = null,
            timeoutMs = 10_000
        )

        val result = runner.run(command)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun non_zero_exit_does_not_throw() {
        val command = Command(
            exe = javaExecutable(),
            args = listOf("-thisIsNotARealOption"),
            cwd = null,
            env = emptyMap(),
            stdin = null,
            timeoutMs = 5_000
        )

        val result = runner.run(command)

        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.isNotEmpty())
    }

    @Test
    fun stdout_and_stderr_are_separated() {
        val result = runner.run(helperCommand("printOutErr"))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("OUT"))
        assertTrue(result.stderr.contains("ERR"))
        assertFalse(result.stdout.contains("ERR"))
    }

    @Test
    fun environment_injection_works() {
        val result = runner.run(helperCommand("env", "FOO", env = mapOf("FOO" to "BAR")))

        assertEquals(0, result.exitCode)
        assertEquals("BAR", result.stdout)
    }

    @Test
    fun stdin_piping_works() {
        val input = "hello\nworld"
        val command = helperCommand("echoStdin").copy(stdin = input.toByteArray())

        val result = runner.run(command)

        assertEquals(0, result.exitCode)
        assertEquals(input, result.stdout)
    }

    @Test
    fun timeout_kills_process() {
        val result = runner.run(helperCommand("sleep", "5000", timeoutMs = 100))

        assertTrue(result.timedOut)
        assertTrue(result.durationMs < 2_000)
    }

    @Test
    fun fatal_spawn_failure_throws() {
        val command = Command(
            exe = "definitely-not-a-real-executable-xyz",
            args = emptyList(),
            cwd = null,
            env = emptyMap(),
            stdin = null,
            timeoutMs = 1_000
        )

        var threw = false
        try {
            runner.run(command)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun invalid_cwd_throws() {
        val command = helperCommand("printOutErr", cwd = tempDir.resolve("non-existent-cwd").toString())

        var threw = false
        try {
            runner.run(command)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun large_output_does_not_deadlock() {
        val result = runner.run(helperCommand("spam", "512", timeoutMs = 5_000))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.length >= 512 * 1024)
        assertFalse(result.timedOut)
    }
}