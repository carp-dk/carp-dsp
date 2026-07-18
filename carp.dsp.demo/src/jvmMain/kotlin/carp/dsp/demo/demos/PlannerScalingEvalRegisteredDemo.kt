package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for PlannerScalingEval in the shared demo runner.
 */
object PlannerScalingEvalRegisteredDemo : CliDemo {
    override val id: String = "planner-scaling-eval"
    override val title: String = "Planner Scaling Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            val mainMethod = Class.forName("carp.dsp.demo.eval.PlannerScalingEvalKt")
                .getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, args.toTypedArray())
        } catch (e: Exception) {
            System.err.println("Error running PlannerScalingEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
