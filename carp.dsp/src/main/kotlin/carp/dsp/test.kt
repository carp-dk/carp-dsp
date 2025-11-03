package carp.dsp

import dk.cachet.carp.analytics.domain.workflow.StepMetadata

class Test {

    fun main() {
        println("Hello, DSP!")
        val stepMetadata = StepMetadata("step1", "Test Step" )
        println("Step Metadata: ${stepMetadata.name}")
    }
}

fun main() {
    val t = Test()
    t.main()
}
