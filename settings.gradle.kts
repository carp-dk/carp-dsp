rootProject.name = "carp-dsp"

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":detekt")
// include(":analytics-core")  // Will be added when we import the code
