package com.intellij.air.thread.view

/**
 * Подставной интерфейс файла вкладки AIR — с тем же полным именем, что у настоящего
 * (`plugins/air-plugin`, PhpStorm 263.8054). Плагин узнаёт вкладку AIR только по имени
 * интерфейса и зовёт `getThreadId()` рефлексией, а в тестовую платформу AIR не загружается.
 * Здесь — ровно то, на что плагин опирается, и ничего больше.
 */
interface AgentThreadViewContentFile {
    fun getThreadId(): String
}
