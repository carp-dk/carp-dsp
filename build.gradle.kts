plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
}

group = "dk.cachet.carp.dsp"
version = project.property("version") as String

// Detekt will be configured after modules are created
