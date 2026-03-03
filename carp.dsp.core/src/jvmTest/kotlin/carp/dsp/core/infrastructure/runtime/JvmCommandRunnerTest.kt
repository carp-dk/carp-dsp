package carp.dsp.core.infrastructure.runtime

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.application.execution.RelativePath
import dk.cachet.carp.analytics.application.plan.CommandSpec
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.tools.ToolProvider
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun cwdWithSpaces(): Path {
        val cwd = tempDir.resolve("cwd with spaces")
        cwd.createDirectories()
        return cwd
    }

    private fun defaultPolicy(timeoutMs: Long? = 10_000, workingDirectory: Path? = null) =
        CommandPolicy(timeoutMs = timeoutMs, workingDirectory = workingDirectory?.let { RelativePath(it.fileName.toString()) })

    private fun ensureHelperCompiled(): Path {
        compiledDir?.let { return it }
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue("JDK is required to compile helper program", compiler != null)

        val outputDir = tempDir.resolve("compiled")
        outputDir.createDirectories()
        val sourceFile = tempDir.resolve("RunnerTestMain.java")
        Files.newBufferedWriter(sourceFile).use { writer ->
            writer.write(helperSource)
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

    private val helperSource: String = """
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

    private fun helperCommand(vararg args: String): CommandSpec {
        val outputDir = ensureHelperCompiled()
        val baseArgs = listOf("-cp", outputDir.toString(), "RunnerTestMain") + args.toList()
        return CommandSpec(
            executable = javaExecutable(),
            args = baseArgs
        )
    }

    @Test
    fun trivial_command_runs_successfully() {
        val command = CommandSpec(
            executable = javaExecutable(),
            args = listOf("-version")
        )

        val result = runner.run(command, defaultPolicy(timeoutMs = 10_000))

        assertEquals(0, result.exitCode)
        assertTrue(result.durationMs >= 0)
        assertTrue(result.stdout.isNotEmpty() || result.stderr.isNotEmpty())
        assertFalse(result.timedOut)
    }

    @Test
    fun args_with_spaces_and_cwd_with_spaces_work() {
        val command = CommandSpec(
            executable = javaExecutable(),
            args = listOf("-Dcarp.test=hello world", "-version")
        )

        val result = runner.run(command, defaultPolicy(timeoutMs = 10_000, workingDirectory = cwdWithSpaces()))

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun non_zero_exit_does_not_throw() {
        val command = CommandSpec(
            executable = javaExecutable(),
            args = listOf("-thisIsNotARealOption")
        )

        val result = runner.run(command, defaultPolicy(timeoutMs = 5_000))

        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.isNotEmpty())
    }

    @Test
    fun stdout_and_stderr_are_separated() {
        val result = runner.run(helperCommand("printOutErr"), defaultPolicy())

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("OUT"))
        assertTrue(result.stderr.contains("ERR"))
        assertFalse(result.stdout.contains("ERR"))
    }

    @Test
    fun timeout_kills_process() {
        val result = runner.run(helperCommand("sleep", "5000"), defaultPolicy(timeoutMs = 100))

        assertTrue(result.timedOut)
        assertTrue(result.durationMs < 2_000)
    }

    @Test
    fun fatal_spawn_failure_throws() {
        val command = CommandSpec(
            executable = "definitely-not-a-real-executable-xyz"
        )

        assertFailsWith<IOException> {
            runner.run(command, defaultPolicy(timeoutMs = 1_000))
        }
    }

    @Test
    fun large_output_does_not_deadlock() {
        val result = runner.run(helperCommand("spam", "512"), defaultPolicy(timeoutMs = 5_000))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.length >= 512 * 1024)
        assertFalse(result.timedOut)
    }

    // ── run(command, policy, workspaceRoot) overload ──────────────────────────

    @Test
    fun run_with_workspace_root_and_no_relative_wd_uses_root_as_cwd() {
        // workingDirectory = null → process runs directly in workspaceRoot
        val policy = CommandPolicy(timeoutMs = 10_000, workingDirectory = null)
        val result = runner.run(helperCommand("printOutErr"), policy, tempDir)

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("OUT"))
        assertTrue(result.stderr.contains("ERR"))
        assertFalse(result.timedOut)
    }

    @Test
    fun run_with_workspace_root_and_relative_wd_resolves_subdir() {
        // workingDirectory set → resolveUnderRoot is exercised inside execute()
        val subdir = RelativePath("subdir")
        val policy = CommandPolicy(timeoutMs = 10_000, workingDirectory = subdir)
        val result = runner.run(helperCommand("printOutErr"), policy, tempDir)

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("OUT"))
        // The subdir should have been created on disk
        assertTrue(tempDir.resolve("subdir").toFile().isDirectory)
        assertFalse(result.timedOut)
    }

    @Test
    fun run_with_workspace_root_reports_non_zero_exit() {
        val policy = CommandPolicy(timeoutMs = 10_000, workingDirectory = null)
        val result = runner.run(helperCommand("exitCode", "42"), policy, tempDir)

        assertEquals(42, result.exitCode)
        assertFalse(result.timedOut)
    }

    // ── null timeout (process.waitFor() branch) ───────────────────────────────

    @Test
    fun null_timeout_waits_indefinitely_and_succeeds() {
        // timeoutMs = null → execute() takes the `process.waitFor()` path
        val policy = CommandPolicy(timeoutMs = null)
        val result = runner.run(helperCommand("sleep", "200"), policy)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.contains("DONE"))
    }

    @Test
    fun null_timeout_captures_non_zero_exit() {
        val policy = CommandPolicy(timeoutMs = null)
        val result = runner.run(helperCommand("exitCode", "3"), policy)

        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
    }

    // ── Custom timeoutExitCode ────────────────────────────────────────────────

    @Test
    fun custom_timeout_exit_code_is_used_on_timeout() {
        val customRunner = JvmCommandRunner(timeoutExitCode = 124)
        val result = customRunner.run(helperCommand("sleep", "5000"), defaultPolicy(timeoutMs = 100))

        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
    }

    // ── resolveUnderRoot ──────────────────────────────────────────────────────

    @Test
    fun resolveUnderRoot_returns_path_inside_root() {
        val rel = RelativePath("a/b/c")
        val resolved = runner.resolveUnderRoot(tempDir, rel)

        assertTrue(resolved.startsWith(tempDir.toAbsolutePath().normalize()))
        assertTrue(resolved.endsWith(Paths.get("a", "b", "c")))
    }

    @Test
    fun resolveUnderRoot_rejects_path_traversal() {
        // RelativePath.init bars '..' at construction time, which is the intended
        // first line of defence. Here we bypass that guard via the JVM inline-class
        // box constructor so we can unit-test resolveUnderRoot's own require in
        // isolation — confirming both layers independently.
        val traversal = "../../etc/passwd"
        val rel = RelativePath::class.java
            .getDeclaredConstructor(String::class.java)
            .also { it.isAccessible = true }
            .newInstance(traversal) as RelativePath

        assertFailsWith<IllegalArgumentException> {
            runner.resolveUnderRoot(tempDir, rel)
        }
    }
}
