package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for [LibraryCleanDemo] in the shared demo runner.
 */
object LibraryCleanRegisteredDemo : Demo {
    override val id: String = "library-clean"
    override val title: String = "Library step demo (resolves and runs `uses:`)"

    override fun run() {
        LibraryCleanDemo.run()
    }
}
