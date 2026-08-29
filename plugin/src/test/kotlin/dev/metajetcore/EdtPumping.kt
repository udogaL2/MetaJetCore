package dev.metajetcore

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil

/**
 * Выполнить блокирующую операцию, не заморозив EDT.
 *
 * Зачем это нужно. Тело платформенного теста выполняется В EDT, а весь код плагина, который
 * трогает терминал, наоборот, уходит в EDT через `invokeAndWait`. Если позвать его прямо из
 * теста, получится взаимоблок: тест держит EDT и ждёт результата, а результат ждёт свободного
 * EDT. Ровно так тест диагностики и повис, когда она стала читать состояние UI.
 *
 * В проде такого не бывает: MCP-запросы приходят с потоков HTTP-сервера, а EDT свободен.
 * Значит правильная модель для теста — запустить работу на постороннем потоке и всё это время
 * прокручивать очередь событий IDE, изображая живую IDE.
 */
fun <T> onPooledThreadPumpingEdt(timeoutMs: Long = 120_000, block: () -> T): T {
    val future = ApplicationManager.getApplication().executeOnPooledThread<T> { block() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!future.isDone) {
        if (System.currentTimeMillis() > deadline) {
            future.cancel(true)
            throw AssertionError("операция не завершилась за ${timeoutMs / 1000} c")
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(5)
    }
    return future.get()
}

/**
 * Крутить события IDE, пока условие не станет истинным.
 *
 * Полезно там, где результат приходит не из нашего вызова, а со стороны: например, шелл во
 * вкладке поднялся, или подставной агент дописал файл в реестр.
 */
fun awaitPumpingEdt(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(20)
    }
    throw AssertionError("не дождались: $what")
}
