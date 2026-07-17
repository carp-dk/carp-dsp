package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for DriftEval in the shared demo runner.
 */
object DriftEvalRegisteredDemo : CliDemo {
    override val id: String = "dependency-drift-eval"
    override val title: String = "Dependency Drift Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            val mainMethod = Class.forName("carp.dsp.demo.eval.DriftEvalKt")
                .getMethod("main")
            mainMethod.invoke(null)
        } catch (e: Exception) {
            System.err.println("Error running DriftEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
