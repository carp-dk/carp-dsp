package carp.dsp.core.infrastructure.execution.handlers

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommandExecutionUtilsTest {

    @Test
    fun `execute command returns output on successful command`() {
        val tempDir = createTempDirectory("command-utils-success-")

        try {
            val script = tempDir.resolve("Hello.java")
            script.writeText(
                """
                class Hello {
                    public static void main(String[] args) {
                        System.out.print("COMMAND_UTILS_OK");
                    }
                }
                """.trimIndent()
            )

            val result = executeCommand(listOf(resolveJavaBinary().toString(), script.toString()))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("COMMAND_UTILS_OK"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute command returns non-zero on failing command`() {
        val result = executeCommand(listOf(resolveJavaBinary().toString(), "--this-option-does-not-exist"))

        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `execute command honors working dir`() {
        val tempDir = createTempDirectory("command-utils-cwd-")

        try {
            val scriptFileName = "PrintCwd.java"
            tempDir.resolve(scriptFileName).writeText(
                """
                class PrintCwd {
                    public static void main(String[] args) {
                        System.out.print(System.getProperty("user.dir"));
                    }
                }
                """.trimIndent()
            )

            val result = executeCommand(
                listOf(resolveJavaBinary().toString(), scriptFileName),
                workingDir = tempDir
            )

            assertEquals(0, result.exitCode)
            assertTrue(normalizePath(result.stdout.trim()).contains(normalizePath(tempDir)))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catch IOException when executable does not exist`() {
        val result = executeCommand(listOf("this-binary-does-not-exist-xyzzy"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `catch IllegalArgumentException when command list is empty`() {
        val result = executeCommand(listOf(""))
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    private fun resolveJavaBinary(): Path {
        val javaHome = Path.of(System.getProperty("java.home"))
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val javaExecutable = if (isWindows) "java.exe" else "java"
        return javaHome.resolve("bin").resolve(javaExecutable)
    }

    private fun normalizePath(path: Path): String =
        normalizePath(path.toAbsolutePath().toString())

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/')
}


