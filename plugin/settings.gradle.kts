rootProject.name = "metajetcore"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Позволяет Gradle самому скачать JDK 21 для toolchain, если его нет в системе.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
