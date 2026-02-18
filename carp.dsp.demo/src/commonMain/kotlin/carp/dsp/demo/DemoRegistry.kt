package carp.dsp.demo
import carp.dsp.demo.api.Demo
import carp.dsp.demo.demos.MinimalAuthorModelDemo


object DemoRegistry {
    val demos: List<Demo> = listOf(
        MinimalAuthorModelDemo,
    )

    fun byId(id: String): Demo? = demos.firstOrNull { it.id == id }
}
