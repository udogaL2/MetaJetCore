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

    /**
     * Подготовка песочницы перед runIde.
     *
     * Свежая песочница встречает модальными диалогами — доверие к проекту, tips of the day,
     * согласие на сбор статистики. В автоматическом прогоне нажать на них некому, и IDE
     * зависает на старте, так и не дойдя до project-сервисов и MCP-сервера.
     */
    val seedSandbox by registering {
        val repoRoot = rootProject.projectDir.parentFile
        val optionsDir = layout.buildDirectory.dir("idea-sandbox").get().asFile

        doLast {
            val targets = optionsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { File(it, "config/options") }
                ?: emptyList()

            for (dir in targets) {
                dir.mkdirs()

                File(dir, "trusted-paths.xml").writeText(
                    """
                    <application>
                      <component name="Trusted.Paths.Settings">
                        <option name="TRUSTED_PATHS">
                          <list>
                            <option value="${repoRoot.absolutePath.replace('\\', '/')}" />
                          </list>
                        </option>
                      </component>
                    </application>
                    """.trimIndent(),
                )

                File(dir, "ide.general.xml").writeText(
                    """
                    <application>
                      <component name="GeneralSettings">
                        <option name="showTipsOnStartup" value="false" />
                        <option name="confirmExit" value="false" />
                        <option name="reopenLastProject" value="false" />
                      </component>
                    </application>
                    """.trimIndent(),
                )

                logger.lifecycle("MetaJetCore: seeded sandbox config at $dir")
            }
        }
    }

    runIde {
        dependsOn(seedSandbox)
        // Без аргумента песочница открывает welcome-экран, а там нет открытого проекта —
        // значит не стартуют project-сервисы и MCP-сервер. Открываем корень репозитория.
        args = listOf(rootProject.projectDir.parentFile.absolutePath)
        // Статистика и прочие сетевые «первый запуск» диалоги в автопрогоне только мешают.
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf("-Didea.is.internal=false", "-Djb.consents.confirmation.enabled=false")
            },
        )
    }

    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}
