package carp.dsp.demo.api

/**
 * Contract for demo implementations that showcase DSP capabilities.
 *
 * @property id Unique id for this demo (e.g., "author-min")
 * @property title Human-readable title (e.g., "Minimal author model demo")
 * @property category Category for organizing demos (e.g., "demo", "eval")
 */
interface Demo {
    val id: String
    val title: String
    val category: String get() = "demo"
    fun run()
}
