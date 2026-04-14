package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for DiafocusDemo in the shared demo runner.
 */
object DiafocusRegisteredDemo : Demo {
    override val id: String = "diafocus"
    override val title: String = "DiaFocus BGM + Steps Demo"

    override fun run() {
        DiafocusDemo.run()
    }
}

