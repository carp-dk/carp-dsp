package carp.dsp.demo
import carp.dsp.demo.api.Demo
import carp.dsp.demo.demos.MinimalAuthorModelDemo
import carp.dsp.demo.demos.PlanningDemo


object DemoRegistry {
    val demos: List<Demo> = listOf(
        MinimalAuthorModelDemo,
        PlanningDemo,
    )

    fun byId(id: String): Demo? = demos.firstOrNull { it.id == id }
}
