package carp.dsp.core.application.execution

import dk.cachet.carp.analytics.application.execution.RunPolicy
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Declarative execution policy for running commands.
 *
 * workingDirectory is serialized as a strictly-relative path.
 * Runtime workspace root is provided by infrastructure at execution time.
 */
@Serializable
data class CommandPolicy(
    override val timeoutMs: Long? = null,
    val workingDirectory: RelativePath? = null
) : RunPolicy {

    /**
     * Resolve [workingDirectory] against a runtime base directory.
     * Ensures resolution cannot escape [workspaceRoot].
     */
    fun resolveWorkingDirectory(workspaceRoot: Path): Path? {
        val rel = workingDirectory ?: return null

        val root = workspaceRoot.normalize()
        val resolved = root.resolve(rel.value).normalize()

        return resolved
    }
}
