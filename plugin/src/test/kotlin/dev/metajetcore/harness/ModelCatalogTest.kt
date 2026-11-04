package dev.metajetcore.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun claudeControlResponseIsParsed() {
        val line = """{"type":"control_response","response":{"subtype":"success","request_id":"mjc-models","response":{"models":[""" +
            """{"value":"default","displayName":"Default (recommended)","description":"Opus 5.5"},""" +
            """{"value":"sonnet","displayName":"Sonnet 5.5","description":"Most efficient"},{"displayName":"без id"}]}}}"""
        val models = ModelCatalog.parse(Harness.CLAUDE, line)!!
        assertEquals(listOf("default", "sonnet"), models.map { it.id })
        assertEquals("Sonnet 5.5", models[1].displayName)
    }

    @Test
    fun codexModelListSkipsHiddenAndMarksDefault() {
        val line = """{"jsonrpc":"2.0","id":2,"result":{"data":[""" +
            """{"id":"gpt-6.1-sol","displayName":"GPT-6.1-Sol","isDefault":true,"hidden":false},""" +
            """{"id":"secret","displayName":"Secret","hidden":true},""" +
            """{"id":"gpt-6-luna","displayName":"GPT-6-Luna"}],"nextCursor":null}}"""
        val models = ModelCatalog.parse(Harness.CODEX, line)!!
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), models.map { it.id })
        assertEquals("по умолчанию в Codex", models[0].description)
    }

    @Test
    fun unrelatedLinesAreIgnored() {
        // Интерактивный шелл может напечатать что угодно до ответа CLI.
        assertNull(ModelCatalog.parse(Harness.CLAUDE, "zsh: no job control in this shell"))
        assertNull(ModelCatalog.parse(Harness.CLAUDE, """{"type":"system","subtype":"init"}"""))
        assertNull(ModelCatalog.parse(Harness.CODEX, """{"jsonrpc":"2.0","id":1,"result":{}}"""))
    }
}
