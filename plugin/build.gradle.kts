import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.metajetcore"
version = "0.5.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Собираем против IntelliJ IDEA: используются только платформенные API,
        // поэтому плагин работает и в PyCharm, и в PhpStorm, и в остальных IDE на платформе.
        //
        // 2026.3 — нижняя граница, и это осознанно. Плагин работает с переработанным
        // терминалом (`TerminalToolWindowTabsManager`, `TerminalView.createSendTextBuilder`),
        // а список харнесов берёт у самой IDE (`TerminalAgent`, `terminalAgentProvider`) —
        // этого API в 2026.2 нет. Держать заодно 262 значило бы вести второй набор путей:
        // статический список харнесов, вкладки без `restoreOnProjectReopen` и т.п.
        //
        // Дистрибутив по умолчанию скачивается, но download.jetbrains.com доступен не
        // отовсюду (отвечает 451), а без него не собирается вообще ничего. Поэтому источник
        // платформы можно подменить уже установленной IDE 263+: свойство `mjcLocalIde` в
        // ~/.gradle/gradle.properties или переменная MJC_LOCAL_IDE. В репозитории пути к
        // чужим машинам не место, отсюда и внешнее свойство.
        val localIde = providers.gradleProperty("mjcLocalIde")
            .orElse(providers.environmentVariable("MJC_LOCAL_IDE"))
        // Версия платформы для сборки и песочницы. По умолчанию — IntelliJ IDEA: EAP-сборки
        // и бесплатные возможности IDEA работают без лицензии, а песочница платной IDE
        // встаёт на модальном диалоге лицензии, и MCP-сервер в ней не отвечает. Пока 2026.3
        // выходит только EAP, номер сборки указан явно; переопределяется свойством
        // `mjcIdeVersion`.
        val ideVersion = providers.gradleProperty("mjcIdeVersion").orElse("263.7307.44")
        if (localIde.isPresent) local(localIde) else intellijIdea(ideVersion)

        // Плагин терминала и его frontend-модуль нужны, чтобы тесты могли поднять настоящую
        // вкладку: без них в тестовой IDE терминала просто нет, и весь терминальный слой
        // остался бы непроверяемым — а он тут главный источник сюрпризов.
        //
        // На КОД это не влияет: обращения к терминалу по-прежнему только через рефлексию
        // (см. terminal/Terminal.kt), импортов его классов в плагине нет. Это то, что
        // позволяет пережить смену экспериментального API: вместо NoSuchMethodError
        // получаем null и переход в ручной режим.
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")

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
            sinceBuild = "263"
            // Верхней границы нет намеренно: плагин не должен отключаться при апгрейде IDE.
            // Это безопасно только потому, что нестабильные API дёргаются рефлексией и
            // деградируют, а не бросают NoClassDefFoundError.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // С локальной IDE проверяем на ней же: recommended() качает несколько
            // дистрибутивов, а там, где download.jetbrains.com недоступен, не скачает ничего.
            val localIde = providers.gradleProperty("mjcLocalIde")
                .orElse(providers.environmentVariable("MJC_LOCAL_IDE"))
            if (localIde.isPresent) local(file(localIde.get())) else recommended()
        }
    }
}

tasks {
    wrapper {
        // IntelliJ Platform Gradle Plugin 2.18 требует Gradle 9+.
        gradleVersion = "9.7.1"
    }

    /**
     * Подготовка песочницы перед runIde.
     *
     * Свежая песочница встречает модальными диалогами — доверие к проекту, tips of the day,
     * согласие на сбор статистики. В автоматическом прогоне нажать на них некому, и IDE
     * зависает на старте, так и не дойдя до project-сервисов и MCP-сервера.
     */
    val seedSandbox = register("seedSandbox") {
        val repoRoot = rootProject.projectDir.parentFile
        // Каталог конфигурации песочницы берём у самой задачи подготовки, а не собираем
        // путь руками: Gradle-плагин 2.x держит песочницу в `.intellijPlatform/sandbox/...`,
        // и прежний путь `build/idea-sandbox` молча указывал в пустоту — песочница вставала
        // на диалоге доверия к проекту, и MCP-сервер так и не поднимался.
        val configDir = named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox")
            .flatMap { it.sandboxConfigDirectory }
        dependsOn("prepareSandbox")

        doLast {
            val targets = listOf(File(configDir.get().asFile, "options"))

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
