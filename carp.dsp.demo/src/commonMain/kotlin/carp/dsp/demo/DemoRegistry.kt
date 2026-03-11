package carp.dsp.demo
import carp.dsp.demo.api.Demo
import carp.dsp.demo.demos.MinimalAuthorModelDemo
import carp.dsp.demo.demos.PlanDiagnosticsDemo
import carp.dsp.demo.demos.PlanningDemo


object DemoRegistry {
    private val _demos: MutableList<Demo> = mutableListOf(
        MinimalAuthorModelDemo,
        PlanningDemo,
        PlanDiagnosticsDemo,
    )

    val demos: List<Demo> get() = _demos

    fun register(demo: Demo) {
        _demos.add(demo)
    }

    fun byId(id: String): Demo? = _demos.firstOrNull { it.id == id }
}
