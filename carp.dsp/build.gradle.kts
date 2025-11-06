plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "carp.dsp"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("dk.cachet.carp:carp-core-common")
    implementation("dk.cachet.carp:carp-core-data")
    implementation("dk.cachet.carp:carp-core-analytics")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
