package carp.dsp.demo

import carp.dsp.demo.api.CliDemo

/**
 * Platform hook for registering platform-specific demos (e.g. those requiring JVM filesystem APIs).
 * Called before the demo dispatcher runs.
 */
expect fun registerPlatformDemos()

/**
 * Platform-agnostic entrypoint. Registers platform demos first, then dispatches.
 */
fun main(args: Array<String>) {
    registerPlatformDemos()
    runDemo(args)
}

/**
 * Shared demo dispatcher. Called by [main] after platform demos are registered.
 */
fun runDemo(args: Array<String>) {
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
            val trailingArgs = args.drop(2)
            if (demo is CliDemo && trailingArgs.isNotEmpty()) {
                demo.run(trailingArgs)
            } else {
                demo.run()
            }
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
