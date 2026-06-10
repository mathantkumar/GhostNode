plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.binarycompatibilityvalidator)
    alias(libs.plugins.dependencycheck)
}

subprojects {
    group = "com.ghostnode"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "maven-publish")

    afterEvaluate {
        configure<org.gradle.api.publish.PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }
        }
    }
}

dependencyCheck {
    failOnError = false
    format = "HTML"
    autoUpdate = false
}
