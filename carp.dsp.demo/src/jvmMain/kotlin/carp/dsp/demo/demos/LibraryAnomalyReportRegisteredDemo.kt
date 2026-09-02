package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for [LibraryAnomalyReportDemo] in the shared demo runner.
 */
object LibraryAnomalyReportRegisteredDemo : Demo {
    override val id: String = "library-anomaly"
    override val title: String = "Anomaly report demo (two signals, detector, via `uses:`)"

    override fun run() {
        LibraryAnomalyReportDemo.run()
    }
}
