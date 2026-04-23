package carp.dsp.demo
import carp.dsp.demo.api.Demo


object DemoRegistry {
    private val _demos: MutableList<Demo> = mutableListOf(
    )

    val demos: List<Demo> get() = _demos

    fun register(demo: Demo) {
        _demos.add(demo)
    }

    fun byId(id: String): Demo? = _demos.firstOrNull { it.id == id }
}
