package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for [LibraryMinimalSummaryDemo] in the shared demo runner.
 */
object LibraryMinimalSummaryRegisteredDemo : Demo {
    override val id: String = "library-minimal"
    override val title: String = "Minimal summary demo (one signal via `uses:`)"

    override fun run() {
        LibraryMinimalSummaryDemo.run()
    }
}
