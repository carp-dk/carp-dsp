package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

/**
 * Registry adapter for ProtocolCouplingEval in the shared demo runner.
 *
 * The eval takes no arguments; [run] with args is accepted and ignored so this
 * adapter matches the other eval adapters (all [CliDemo]), which keeps the
 * `run-all-evals` list homogeneous.
 */
object ProtocolCouplingEvalRegisteredDemo : CliDemo {
    override val id: String = "protocol-coupling-eval"
    override val title: String = "Protocol Coupling Evaluation"
    override val category: String = "eval"

    override fun run() {
        run(emptyList())
    }

    override fun run(args: List<String>) {
        @Suppress("BAN_KOTLIN_TRY_CATCH")
        try {
            Class.forName("carp.dsp.demo.eval.ProtocolCouplingEvalKt")
                .getMethod("main")
                .invoke(null)
        } catch (e: Exception) {
            System.err.println("Error running ProtocolCouplingEval: ${e.message}")
            e.printStackTrace()
        }
    }
}
