plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.collections.immutable)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
