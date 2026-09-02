package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for MobgapTimedEval in the shared demo runner.
 */
object MobgapTimedEvalRegisteredDemo : CliDemo {
    override val id: String = "mobgap-timed-eval"
    override val title: String = "Mobgap Timed Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            val mainMethod = Class.forName("carp.dsp.demo.eval.MobgapTimedEvalKt")
                .getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, args.toTypedArray())
        } catch (e: Exception) {
            System.err.println("Error running MobgapTimedEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
