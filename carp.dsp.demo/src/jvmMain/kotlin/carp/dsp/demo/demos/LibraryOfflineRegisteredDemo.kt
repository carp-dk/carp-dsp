package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for [LibraryOfflineDemo] in the shared demo runner.
 */
object LibraryOfflineRegisteredDemo : Demo {
    override val id: String = "library-offline"
    override val title: String = "Offline library demo (`uses:` pipeline, no network)"

    override fun run() {
        LibraryOfflineDemo.run()
    }
}
