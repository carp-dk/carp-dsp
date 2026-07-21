package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter to run all evaluation demos sequentially.
 */
object RunAllEvalsRegisteredDemo : CliDemo {
    override val id: String = "run-all-evals"
    override val title: String = "Run All Evaluations"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        val evals: List<Pair<String, CliDemo>> = listOf(
            "planner-determinism-eval" to PlannerDeterminismEvalRegisteredDemo,
            "planner-scaling-eval" to PlannerScalingEvalRegisteredDemo,
            "protocol-coupling-eval" to ProtocolCouplingEvalRegisteredDemo,
            "error-detection-eval" to ErrorDetectionEvalRegisteredDemo,
            "step-reuse-eval" to StepReuseEvalRegisteredDemo,
            "mobgap-timed-eval" to MobgapTimedEvalRegisteredDemo,
            "dependency-drift-eval" to DriftEvalRegisteredDemo
        )

        println("Running all evaluations...\n")
        for ((evalName, evalDemo) in evals) {
            println("=" * 70)
            println("Starting: $evalName")
            println("=" * 70)
            try {
                if (args.isNotEmpty()) {
                    evalDemo.run(args)
                } else {
                    evalDemo.run()
                }
            } catch (e: Exception) {
                System.err.println("Error in $evalName: ${e.message}")
                e.printStackTrace()
            }
            println()
        }
        println("=" * 70)
        println("All evaluations completed")
        println("=" * 70)
    }
}

private operator fun String.times(count: Int): String = repeat(count)
