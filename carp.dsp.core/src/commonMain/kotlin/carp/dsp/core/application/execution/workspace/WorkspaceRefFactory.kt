package carp.dsp.core.application.execution.workspace

import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.common.application.UUID

/**
 * Factory for creating [ResourceRef] instances from ExecutionWorkspace-relative paths.
 *
 * This utility ensures all artefact paths are:
 * - Relative to ExecutionWorkspace.executionRoot
 * - Using [ResourceKind.RELATIVE_PATH]
 * - Properly validated to prevent path traversal attacks
 *
 * All paths produced by this factory are guaranteed to:
 * - Use forward slashes as path separators
 * - Not contain ".." segments
 * - Not be absolute paths
 * - Not contain ":" (prevents Windows drive tricks like C:\...)
 */
object WorkspaceRefFactory {

    /**
     * Converts a workspace-relative path string to a ResourceRef.
     *
     * Normalizes path separators first, then validates, so that validation
     * operates on a canonical form.
     *
     * @param relativePath The path relative to ExecutionWorkspace.executionRoot
     * @return A ResourceRef with kind=RELATIVE_PATH
     * @throws IllegalArgumentException if path is empty, absolute, contains "..", or contains ":"
     */
    fun toWorkspaceRelative(relativePath: String): ResourceRef {
        val normalized = normalize(relativePath)
        validateNormalizedPath(normalized)
        return ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = normalized
        )
    }

    /**
     * Creates a ResourceRef for an output artefact under a specific step.
     *
     * Builds the path: steps/{stepMetadata}/outputs/{relativeFileName}
     *
     * @param stepId The step ID that produced the artefact
     * @param relativeFileName The filename or relative path within the outputs directory
     * @return A ResourceRef with kind=RELATIVE_PATH and value relative to executionRoot
     * @throws IllegalArgumentException if filename is empty, absolute, contains "..", or contains ":"
     */
    fun stepOutputRef(
        stepId: UUID,
        relativeFileName: String
    ): ResourceRef {
        val normalizedFileName = normalize(relativeFileName)
        validateNormalizedPath(normalizedFileName)
        val fullPath = "steps/$stepId/outputs/$normalizedFileName"
        return ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = fullPath
        )
    }

    /**
     * Normalizes path separators to forward slashes.
     */
    private fun normalize(path: String): String = path.replace("\\", "/")

    /**
     * Validates a normalized (forward-slash) path is safe.
     *
     * Must be called after [normalize] so that only "/" needs to be checked as a separator.
     *
     * @throws IllegalArgumentException if path is empty, absolute, contains "..", or contains ":"
     */
    private fun validateNormalizedPath(path: String) {
        require(path.isNotEmpty()) {
            "Path must not be empty"
        }

        require(!path.contains(":")) {
            "Path must not contain ':' (possible Windows drive prefix): $path"
        }

        require(!path.startsWith("/")) {
            "Path must be relative, not absolute: $path"
        }

        // After normalization only "/" remains as separator, so split on "/" is sufficient.
        val parts = path.split("/")
        require(!parts.contains("..")) {
            "Path traversal detected: $path"
        }
    }
}

