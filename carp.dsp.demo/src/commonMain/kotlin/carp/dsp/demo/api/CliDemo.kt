package carp.dsp.demo.api

/**
 * Optional extension for demos which accept trailing CLI arguments.
 */
interface CliDemo : Demo {
    fun run(args: List<String>)
}

