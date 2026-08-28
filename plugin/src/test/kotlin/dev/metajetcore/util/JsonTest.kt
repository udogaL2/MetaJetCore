package dev.metajetcore.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun parsesScalars() {
        assertEquals(Json.Null, Json.parse("null"))
        assertEquals(true, Json.parse("true").asBoolean)
        assertEquals(false, Json.parse("false").asBoolean)
        assertEquals(42, Json.parse("42").asInt)
        assertEquals(-7, Json.parse("-7").asInt)
        assertEquals("hi", Json.parse("\"hi\"").asString)
    }

    @Test
    fun parsesNestedStructures() {
        val value = Json.parse("""{"a":[1,2,{"b":"c"}],"d":null}""")
        assertEquals(3, value["a"]?.asList?.size)
        assertEquals("c", value["a"]?.asList?.get(2)?.get("b")?.asString)
        assertEquals(Json.Null, value["d"])
    }

    @Test
    fun handlesEscapesBothWays() {
        val original = "quote\" backslash\\ newline\n tab\t кириллица"
        val rendered = Json.of(original).render()
        assertEquals(original, Json.parse(rendered).asString)
    }

    @Test
    fun roundTripsObjectPreservingKeyOrder() {
        val source = """{"z":1,"a":{"nested":[true,false]},"m":"x"}"""
        assertEquals(source, Json.parse(source).render())
    }

    @Test
    fun rendersIntegralNumbersWithoutDecimalPoint() {
        // Иначе в JSON-RPC id уедет как 1.0 и клиент не сопоставит ответ с запросом.
        assertEquals("1", Json.of(1).render())
        assertEquals("1", Json.parse("1").render())
    }

    @Test
    fun parseOrNullSwallowsMalformedInput() {
        assertNull(Json.parseOrNull("{"))
        assertNull(Json.parseOrNull("{\"a\":}"))
        assertNull(Json.parseOrNull(""))
        assertNull(Json.parseOrNull("{} trailing"))
    }

    @Test
    fun accessorsReturnNullOnTypeMismatch() {
        val value = Json.parse("""{"a":"text"}""")
        assertNull(value["a"]?.asInt)
        assertNull(value["missing"])
        assertNull(value.asList)
    }

    @Test
    fun escapesControlCharactersAsUnicode() {
        val withNul = "a" + 0.toChar() + "b"
        val rendered = Json.of(withNul).render()
        assertTrue(rendered, rendered.contains("\\u0000"))
        assertEquals(withNul, Json.parse(rendered).asString)

        // Form feed уезжает тем же путём: отдельной ветки для него в кодировщике нет.
        val withFormFeed = "a" + 12.toChar() + "b"
        assertEquals(withFormFeed, Json.parse(Json.of(withFormFeed).render()).asString)

        // А обратный разбор \f обязан работать: его пишут другие кодировщики.
        assertEquals(withFormFeed, Json.parse("\"a\\fb\"").asString)
    }
}
