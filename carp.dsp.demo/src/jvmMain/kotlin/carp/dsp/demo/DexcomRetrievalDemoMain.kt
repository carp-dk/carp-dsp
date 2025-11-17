package carp.dsp.demo

import carp.dsp.core.domain.execution.JvmSequentialExecutionStrategy
import dk.cachet.carp.analytics.application.data.DataRegistry

/**
 * JVM entry point for the DEXCOM retrieval demo.
 *
 * Run this from the command line:
 * ```
 * ./gradlew :carp.dsp.demo:jvmRun
 * ```
 *
 * Or from IDE: Right-click and select "Run"
 */
fun main() {
    // Create JVM-specific strategy with HTTP download support
    val dataRegistry = DataRegistry()
    val strategy = JvmSequentialExecutionStrategy(dataRegistry)

    // Execute the demo
    DexcomRetrievalDemo.execute(strategy)
}

