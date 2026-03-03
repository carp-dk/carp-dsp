package carp.dsp.core.application.execution.workspace

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute

/**
 * Helper class for safe filesystem path operations within workspace environments.
 *
 * This class provides path normalization, validation, and security features to prevent
 * path traversal attacks and ensure all resolved paths remain within the designated
 * workspace boundaries.
 *
 * @param baseWorkspaceRoot The base directory that contains all workspaces
 */
class FileSystemPathResolver(
    private val baseWorkspaceRoot: Path
) {

    init {
        require(baseWorkspaceRoot.isAbsolute) {
            "Base workspace root must be an absolute path: $baseWorkspaceRoot"
        }
    }

    /**
     * Resolves the execution root path from a logical root identifier.
     *
     * @param executionRoot The logical root identifier (typically a UUID string)
     * @return The absolute Path to the execution root directory
     */
    fun resolveExecutionRoot(executionRoot: String): Path {
        return baseWorkspaceRoot.resolve(executionRoot).normalize().absolute()
    }

    /**
     * Validates and resolves a relative path within a given root directory.
     *
     * This method performs several security checks:
     * 1. Normalizes the input path to resolve any ".." or "." components
     * 2. Resolves the path against the provided root
     * 3. Ensures the resolved path is still contained within the root
     *
     * @param root The root directory that should contain the resolved path
     * @param relativePath The relative path to resolve and validate
     * @return The absolute, validated Path
     * @throws SecurityException if the path attempts to escape the root directory
     * @throws IllegalArgumentException if the relativePath is absolute
     */
    fun validateAndResolve(root: Path, relativePath: String): Path {
        require(root.isAbsolute) {
            "Root path must be absolute: $root"
        }

        val inputPath = Paths.get(relativePath)

        // Reject absolute paths - all artefact paths should be relative
        require(!inputPath.isAbsolute) { "Artifact paths must be relative, got: $relativePath" }

        // Normalize and resolve the path
        val resolvedPath = root.resolve(inputPath).normalize().absolute()

        // Security check: ensure the resolved path is still within the root
        if (!resolvedPath.startsWith(root.normalize().absolute())) {
            throw SecurityException(
                "Path traversal detected: '$relativePath' resolves outside root directory. " +
                "Resolved: $resolvedPath, Root: ${root.absolute()}"
            )
        }

        return resolvedPath
    }

    /**
     * Normalizes a filesystem path by resolving "." and ".." components.
     *
     * @param path The path to normalize
     * @return The normalized path
     */
    fun normalizePath(path: Path): Path {
        return path.normalize()
    }

    /**
     * Checks if a given path is contained within another path.
     *
     * @param container The container path that should contain the other path
     * @param contained The path to check for containment
     * @return true if contained is within container, false otherwise
     */
    fun isContainedWithin(container: Path, contained: Path): Boolean {
        val normalizedContainer = container.normalize().absolute()
        val normalizedContained = contained.normalize().absolute()
        return normalizedContained.startsWith(normalizedContainer)
    }

    /**
     * Creates a relative path from a root to a target path.
     *
     * @param root The root path to relativize from
     * @param target The target path to relativize
     * @return The relative path from root to target
     * @throws IllegalArgumentException if target is not within root
     */
    fun relativize(root: Path, target: Path): Path {
        val normalizedRoot = root.normalize().absolute()
        val normalizedTarget = target.normalize().absolute()

        require(isContainedWithin(normalizedRoot, normalizedTarget)) {
            "Target path is not within root: target=$target, root=$root"
        }

        return normalizedRoot.relativize(normalizedTarget)
    }
}
