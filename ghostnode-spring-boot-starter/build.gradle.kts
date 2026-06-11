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
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.core)
    implementation(kotlin("reflect"))
    
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
