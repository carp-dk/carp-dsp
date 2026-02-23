package carp.dsp.core.application.execution

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelativePathTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `round-trip serialization preserves value`() {
        val original = RelativePath("a/b/c.txt")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<RelativePath>(encoded)
        assertEquals(original, decoded)
        assertEquals("a/b/c.txt", decoded.toString())
    }

    @Test
    fun `rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { RelativePath("") }
        assertFailsWith<IllegalArgumentException> { RelativePath(" ") }
    }

    @Test
    fun `rejects absolute and drive paths`() {
        assertFailsWith<IllegalArgumentException> { RelativePath("/tmp/file.txt") }
        assertFailsWith<IllegalArgumentException> { RelativePath("C:/temp/file.txt") }
        assertFailsWith<IllegalArgumentException> { RelativePath("D:\\temp\\file.txt") }
    }

    @Test
    fun `rejects traversal segments`() {
        assertFailsWith<IllegalArgumentException> { RelativePath("../file.txt") }
        assertFailsWith<IllegalArgumentException> { RelativePath("a/../file.txt") }
        assertFailsWith<IllegalArgumentException> { RelativePath("a..b/file.txt") }
    }
}

