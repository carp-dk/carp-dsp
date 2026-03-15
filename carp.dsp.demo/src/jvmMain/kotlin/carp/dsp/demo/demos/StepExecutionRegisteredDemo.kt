package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for StepExecutionDemo in the shared demo runner.
 */
object StepExecutionRegisteredDemo : CliDemo {
    override val id: String = "step-execution-demo"
    override val title: String = "Step execution demo (runs real command)"

    override fun run() {
        StepExecutionDemo.run()
    }

    override fun run(args: List<String>) {
        StepExecutionDemo.run(args)
    }
}

