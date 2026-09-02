package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo

/**
 * Registry adapter for [LibraryReuseDemo] in the shared demo runner.
 */
object LibraryReuseRegisteredDemo : Demo {
    override val id: String = "library-reuse"
    override val title: String = "Library reuse demo (activity summary via `uses:`)"

    override fun run() {
        LibraryReuseDemo.run()
    }
}
