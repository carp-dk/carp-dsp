package carp.dsp.demo

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        printHelp()
        return
    }

    when (args[0]) {
        "list" -> listDemos()
        "run" -> {
            val id = args.getOrNull(1)
            if (id == null) {
                println("Missing demo id.\n")
                listDemos()
                return
            }
            val demo = DemoRegistry.byId(id)
            if (demo == null) {
                println("Unknown demo id: $id\n")
                listDemos()
                return
            }
            println("== ${demo.title} (${demo.id}) ==")
            demo.run()
        }
        else -> {
            println("Unknown command: ${args[0]}\n")
            printHelp()
        }
    }
}

private fun listDemos() {
    println("Available demos:")
    DemoRegistry.demos.forEach { println(" - ${it.id}: ${it.title}") }
}

private fun printHelp() {
    println(
        """
        CARP-DSP Demo Runner

        Usage:
          demo list
          demo run <id>

        """.trimIndent()
    )
    listDemos()
}
