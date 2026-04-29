package carp.dsp.demo.demos

import carp.dsp.demo.api.CliDemo

object HrActivityRegisteredDemo : CliDemo {
    override val id: String = "hr-activity"
    override val title: String = "Heart Activity Summary Demo"

    override fun run() = HrActivityDemo.run()
    override fun run(args: List<String>) = HrActivityDemo.run(args)
}
