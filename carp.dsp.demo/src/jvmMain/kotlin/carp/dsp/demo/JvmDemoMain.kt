package carp.dsp.demo
import carp.dsp.demo.demos.StepExecutionRegisteredDemo
import carp.dsp.demo.demos.DiafocusRegisteredDemo
import carp.dsp.demo.demos.DbdpCovidRegisteredDemo
import carp.dsp.demo.demos.MobgapRegisteredDemo
import carp.dsp.demo.demos.HrActivityRegisteredDemo

/**
 * JVM actual: registers filesystem-backed demos before the shared dispatcher runs.
 */
actual fun registerPlatformDemos() {
    DemoRegistry.register(StepExecutionRegisteredDemo)
    DemoRegistry.register(DiafocusRegisteredDemo)
    DemoRegistry.register(DbdpCovidRegisteredDemo)
    DemoRegistry.register(MobgapRegisteredDemo)
    DemoRegistry.register(HrActivityRegisteredDemo)
}

