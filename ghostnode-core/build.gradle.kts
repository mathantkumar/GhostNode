plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.collections.immutable)
    api(libs.kotlinx.serialization.json)
    api(libs.micrometer.core)
    api(libs.slf4j.api)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
