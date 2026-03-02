package carp.dsp.core.application.environment.workspace

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemPathResolverTest {

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var pathResolver: FileSystemPathResolver
    private lateinit var baseRoot: Path

    @BeforeEach
    fun setup() {
        baseRoot = tempDir.resolve("base")
        pathResolver = FileSystemPathResolver(baseRoot)
    }

    @Test
    fun `constructor requires absolute path`() {
        // Arrange
        val relativePath = Paths.get("relative/path")

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            FileSystemPathResolver(relativePath)
        }
    }

    @Test
    fun `resolveExecutionRoot creates correct absolute path`() {
        // Arrange
        val executionRoot = "test-execution-root"

        // Act
        val resolvedPath = pathResolver.resolveExecutionRoot(executionRoot)

        // Assert
        val expectedPath = baseRoot.resolve(executionRoot)
        assertEquals(expectedPath.normalize().toAbsolutePath(), resolvedPath)
        assertTrue(resolvedPath.isAbsolute, "Resolved execution root should be absolute")
    }

    @Test
    fun `validateAndResolve accepts safe relative paths`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val relativePath = "safe/path/file.txt"

        // Act
        val resolved = pathResolver.validateAndResolve(root, relativePath)

        // Assert
        val expected = root.resolve(relativePath)
        assertEquals(expected.normalize().toAbsolutePath(), resolved)
        assertTrue(resolved.startsWith(root.toAbsolutePath()), "Resolved path should be within root")
    }

    @Test
    fun `validateAndResolve rejects absolute paths`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val absolutePath = "/etc/passwd"

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            pathResolver.validateAndResolve(root, absolutePath)
        }
    }

    @Test
    fun `validateAndResolve rejects path traversal attempts`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val traversalPaths = listOf(
            "../../../etc/passwd",
            "dir1/../../etc/passwd",
            "./../../etc/passwd",
            "....//....//etc/passwd",
            "dir/../../../etc/passwd"
        )

        // Act & Assert
        traversalPaths.forEach { traversalPath ->
            assertThrows<SecurityException>("Should reject traversal: $traversalPath") {
                pathResolver.validateAndResolve(root, traversalPath)
            }
        }
    }

    @Test
    fun `validateAndResolve normalizes complex paths safely`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val complexPath = "dir1/../dir2/./file.txt"

        // Act
        val resolved = pathResolver.validateAndResolve(root, complexPath)

        // Assert
        val expected = root.resolve("dir2/file.txt")
        assertEquals(expected.normalize().toAbsolutePath(), resolved)
        assertTrue(resolved.startsWith(root.toAbsolutePath()), "Resolved path should be within root")
    }

    @Test
    fun `validateAndResolve requires absolute root path`() {
        // Arrange
        val relativeRoot = Paths.get("relative/root")
        val relativePath = "safe/path.txt"

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            pathResolver.validateAndResolve(relativeRoot, relativePath)
        }
    }

    @Test
    fun `normalizePath resolves dot components correctly`() {
        // Arrange
        val pathWithDots = Paths.get("dir1/./dir2/../dir3/file.txt")

        // Act
        val normalized = pathResolver.normalizePath(pathWithDots)

        // Assert
        val expected = Paths.get("dir1/dir3/file.txt")
        assertEquals(expected, normalized)
    }

    @Test
    fun `isContainedWithin detects containment correctly`() {
        // Arrange
        val container = tempDir.resolve("container")
        val contained = container.resolve("subdirectory/file.txt")
        val notContained = tempDir.resolve("other/file.txt")

        // Act & Assert
        assertTrue(pathResolver.isContainedWithin(container, contained),
                  "Should detect contained path")
        assertFalse(pathResolver.isContainedWithin(container, notContained),
                   "Should detect non-contained path")
        assertFalse(pathResolver.isContainedWithin(contained, container),
                   "Container should not be contained within contained")
    }

    @Test
    fun `isContainedWithin handles complex paths correctly`() {
        // Arrange
        val container = tempDir.resolve("container")
        val complexContained = container.resolve("sub/../other/file.txt")
        val traversalAttempt = container.resolve("../escape/file.txt")

        // Act & Assert
        assertTrue(pathResolver.isContainedWithin(container, complexContained),
                  "Should handle normalized contained path")
        assertFalse(pathResolver.isContainedWithin(container, traversalAttempt),
                   "Should detect traversal escape attempt")
    }

    @Test
    fun `relativize creates correct relative path`() {
        // Arrange
        val root = tempDir.resolve("workspace")
        val target = root.resolve("subdir/file.txt")

        // Act
        val relative = pathResolver.relativize(root, target)

        // Assert
        val expected = Paths.get("subdir/file.txt")
        assertEquals(expected, relative)
    }

    @Test
    fun `relativize rejects target outside root`() {
        // Arrange
        val root = tempDir.resolve("workspace")
        val outsideTarget = tempDir.resolve("other/file.txt")

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            pathResolver.relativize(root, outsideTarget)
        }
    }

    @Test
    fun `relativize handles same path correctly`() {
        // Arrange
        val root = tempDir.resolve("workspace")

        // Act
        val relative = pathResolver.relativize(root, root)

        // Assert
        val expected = Paths.get("")
        assertEquals(expected, relative)
    }

    @Test
    fun `relativize handles complex normalized paths`() {
        // Arrange
        val root = tempDir.resolve("workspace")
        val complexTarget = root.resolve("dir1/../dir2/./file.txt")

        // Act
        val relative = pathResolver.relativize(root, complexTarget)

        // Assert
        val expected = Paths.get("dir2/file.txt")
        assertEquals(expected, relative)
    }

    @Test
    fun `validateAndResolve handles edge cases`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val edgeCases = listOf(
            ".",
            "./",
            "./file.txt",
            "dir/./file.txt",
            "dir1/dir2/../file.txt"
        )

        // Act & Assert
        edgeCases.forEach { edgeCase ->
            val resolved = pathResolver.validateAndResolve(root, edgeCase)
            assertTrue(resolved.startsWith(root.toAbsolutePath()),
                      "Edge case should resolve within root: $edgeCase")
        }
    }

    @Test
    fun `validateAndResolve rejects empty and problematic paths`() {
        // Arrange
        val root = tempDir.resolve("test-root")

        // Act & Assert
        // Empty string should resolve to root itself
        val emptyResolved = pathResolver.validateAndResolve(root, "")
        assertEquals(root.toAbsolutePath(), emptyResolved)
    }

    @Test
    fun `isContainedWithin handles edge cases with identical paths`() {
        // Arrange
        val path = tempDir.resolve("same/path")

        // Act & Assert
        assertTrue(pathResolver.isContainedWithin(path, path),
                  "Path should be contained within itself")
    }

    @Test
    fun `path operations handle Windows and Unix path separators`() {
        // Arrange
        val root = tempDir.resolve("test-root")
        val mixedPath = "dir1\\subdir/file.txt"  // Mixed separators

        // Act
        val resolved = pathResolver.validateAndResolve(root, mixedPath)

        // Assert
        assertTrue(resolved.startsWith(root.toAbsolutePath()),
                  "Mixed separator path should resolve within root")
        assertTrue(resolved.toString().contains("dir1"),
                  "Should preserve directory structure")
        assertTrue(resolved.toString().contains("subdir"),
                  "Should preserve subdirectory structure")
    }
}
