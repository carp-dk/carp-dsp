plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:${libs.versions.detekt.get()}")

    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${libs.versions.detekt.get()}")
    testImplementation(kotlin("test"))
}

