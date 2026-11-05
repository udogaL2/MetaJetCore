package dev.metajetcore.terminal

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project

/**
 * Менеджер редакторов проекта — через `project.getService`, а не `FileEditorManager.getInstance`.
 *
 * Выглядит как лишняя обёртка над одной строкой, но это вопрос совместимости внутри одной
 * ветки платформы. Между 263.6949 и 263.7307 `FileEditorManager` переписали с Java на Kotlin.
 * Kotlin-код, собранный против новой сборки, зовёт `FileEditorManager.Companion.getInstance`,
 * а в старой `Companion` нет — `NoSuchFieldError` на каждом обращении (живой случай в
 * PhpStorm 263.6949: плагин не видел ни одного терминала в редакторе, и агент открывался не
 * рядом с оркестратором). `getService` — метод интерфейса `ComponentManager`, он одинаков в
 * любой сборке, на каком бы языке ни был написан сам менеджер. То же для `getInstanceEx`.
 *
 * Правило шире этого класса: статический `getInstance` платформенного класса — потенциальный
 * `Companion` в следующей сборке. Ловит такое Plugin Verifier, если гонять его и против самой
 * старой поддерживаемой сборки (см. `mjcVerifyIdes` в build.gradle.kts).
 */
internal object Editors {
    fun of(project: Project): FileEditorManager = project.getService(FileEditorManager::class.java)

    /** null — менеджер не расширенный (так бывает в тестах); окон редактора тогда нет. */
    fun ex(project: Project): FileEditorManagerEx? = of(project) as? FileEditorManagerEx
}
