package carp.dsp.demo

import carp.dsp.demo.demos.PlanToWorkspaceDemoFactory
import carp.dsp.demo.demos.StepExecutionRegisteredDemo

/**
 * JVM actual: registers filesystem-backed demos before the shared dispatcher runs.
 */
actual fun registerPlatformDemos() {
    DemoRegistry.register(PlanToWorkspaceDemoFactory.create())
    DemoRegistry.register(StepExecutionRegisteredDemo)
}

