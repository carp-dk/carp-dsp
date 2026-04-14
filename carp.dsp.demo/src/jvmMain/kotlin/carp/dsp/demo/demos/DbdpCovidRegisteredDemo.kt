package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for DbdpCovidDemo in the shared demo runner.
 */
object DbdpCovidRegisteredDemo : Demo {
    override val id: String = "dbdp-covid"
    override val title: String = "DBDP COVID HR + Steps Demo"

    override fun run() {
        DbdpCovidDemo.run()
    }
}

