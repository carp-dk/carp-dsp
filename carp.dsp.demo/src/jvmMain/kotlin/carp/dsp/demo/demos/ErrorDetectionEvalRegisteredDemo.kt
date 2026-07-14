package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for ErrorDetectionEval in the shared demo runner.
 */
object ErrorDetectionEvalRegisteredDemo : CliDemo {
    override val id: String = "error-detection-eval"
    override val title: String = "Error Detection Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            val mainMethod = Class.forName("carp.dsp.demo.eval.ErrorDetectionEvalKt")
                .getMethod("main")
            mainMethod.invoke(null)
        } catch (e: Exception) {
            System.err.println("Error running ErrorDetectionEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
