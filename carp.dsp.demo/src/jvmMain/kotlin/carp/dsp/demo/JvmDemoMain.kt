package carp.dsp.demo

import carp.dsp.demo.demos.PlanToWorkspaceDemoFactory

/**
 * JVM actual: registers filesystem-backed demos before the shared dispatcher runs.
 */
actual fun registerPlatformDemos() {
    DemoRegistry.register(PlanToWorkspaceDemoFactory.create())
}

