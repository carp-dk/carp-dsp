package carp.dsp.demo
import carp.dsp.demo.demos.StepExecutionRegisteredDemo
import carp.dsp.demo.demos.DiafocusRegisteredDemo
import carp.dsp.demo.demos.DbdpCovidRegisteredDemo
import carp.dsp.demo.demos.MobgapRegisteredDemo
import carp.dsp.demo.demos.HrActivityRegisteredDemo
import carp.dsp.demo.demos.MobgapTimedEvalRegisteredDemo
import carp.dsp.demo.demos.PlannerDeterminismEvalRegisteredDemo
import carp.dsp.demo.demos.PlannerScalingEvalRegisteredDemo
import carp.dsp.demo.demos.ProtocolCouplingEvalRegisteredDemo
import carp.dsp.demo.demos.ErrorDetectionEvalRegisteredDemo
import carp.dsp.demo.demos.StepReuseEvalRegisteredDemo
import carp.dsp.demo.demos.DriftEvalRegisteredDemo
import carp.dsp.demo.demos.RunAllEvalsRegisteredDemo

/**
 * JVM actual: registers filesystem-backed demos before the shared dispatcher runs.
 *
 * See [DemoMain] for how to run demos and CLI arguments.
 */
actual fun registerPlatformDemos() {
    DemoRegistry.register(StepExecutionRegisteredDemo)
    DemoRegistry.register(DiafocusRegisteredDemo)
    DemoRegistry.register(DbdpCovidRegisteredDemo)
    DemoRegistry.register(MobgapRegisteredDemo)
    DemoRegistry.register(HrActivityRegisteredDemo)
    DemoRegistry.register(PlannerDeterminismEvalRegisteredDemo)
    DemoRegistry.register(PlannerScalingEvalRegisteredDemo)
    DemoRegistry.register(ProtocolCouplingEvalRegisteredDemo)
    DemoRegistry.register(MobgapTimedEvalRegisteredDemo)
    DemoRegistry.register(ErrorDetectionEvalRegisteredDemo)
    DemoRegistry.register(StepReuseEvalRegisteredDemo)
    DemoRegistry.register(DriftEvalRegisteredDemo)
    DemoRegistry.register(RunAllEvalsRegisteredDemo)
}

