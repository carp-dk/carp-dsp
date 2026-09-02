package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for StepReuseEval in the shared demo runner.
 */
object StepReuseEvalRegisteredDemo : CliDemo {
    override val id: String = "step-reuse-eval"
    override val title: String = "Step Reuse Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            val mainMethod = Class.forName("carp.dsp.demo.eval.StepReuseEvalKt")
                .getMethod("main")
            mainMethod.invoke(null)
        } catch (e: Exception) {
            System.err.println("Error running StepReuseEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
