plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    group = "com.ghostnode"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
