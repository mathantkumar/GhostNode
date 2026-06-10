plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ghostnode-core"))
    
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter)
    implementation(libs.micrometer.core)
    
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
