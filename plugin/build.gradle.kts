import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.metajetcore"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Собираем против IntelliJ IDEA Community: используются только платформенные API,
        // поэтому плагин работает и в PyCharm, и в PhpStorm, и в остальных IDE на платформе.
        intellijIdeaCommunity("2025.1")

        // Плагин терминала НАМЕРЕННО не объявлен зависимостью сборки: весь доступ к нему
        // идёт через рефлексию (см. terminal/ReflectiveTerminalBackend.kt). Это то, что
        // позволяет пережить смену экспериментального Terminal API между версиями IDE.

        testFramework(TestFrameworkType.Platform)
    }

    // Тестовые зависимости в поставку плагина не попадают, поэтому конфликтовать
    // с платформой в рантайме им негде.
    testImplementation("junit:junit:4.13.2")
}

// В рантайме — никаких сторонних библиотек: ни JSON, ни HTTP. Всё либо из JDK, либо своё.
// Каждая внешняя зависимость — это ещё один способ конфликтнуть с платформой при апгрейде.

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.metajetcore.plugin"
        name = "MetaJetCore"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "251"
            // Верхней границы нет намеренно: плагин не должен отключаться при апгрейде IDE.
            // Это безопасно только потому, что нестабильные API дёргаются рефлексией и
            // деградируют, а не бросают NoClassDefFoundError.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }

    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}
