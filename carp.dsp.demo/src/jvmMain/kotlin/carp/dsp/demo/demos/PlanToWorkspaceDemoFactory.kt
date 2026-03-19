package carp.dsp.demo.demos

import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import java.nio.file.Paths

/**
 * JVM factory that wires the concrete [DefaultWorkspaceManager] into [PlanToWorkspaceDemo].
 *
 * The base workspace root defaults to a `dsp-workspaces` directory next to the working directory,
 * which keeps demo output visible and reproducible without polluting the system temp dir.
 */
object PlanToWorkspaceDemoFactory {

    fun create(): PlanToWorkspaceDemo {
        val baseRoot = Paths.get(System.getProperty("user.dir"), "dsp-workspaces").toAbsolutePath()
        val workspaceManager = DefaultWorkspaceManager(baseRoot)
        return PlanToWorkspaceDemo(workspaceManager)
    }
}

