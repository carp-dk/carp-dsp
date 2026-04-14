package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for MobgapDemo in the shared demo runner.
 */
object MobgapRegisteredDemo : Demo {
    override val id: String = "mobgap"
    override val title: String = "Mobgap Gait Analysis Demo"

    override fun run() {
        MobgapDemo.run()
    }
}
