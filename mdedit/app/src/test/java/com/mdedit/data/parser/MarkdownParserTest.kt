package com.mdedit.data.parser

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarkdownParserTest {

    private lateinit var parser: MarkdownParser
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        parser = MarkdownParser(testDispatcher)
    }

    @Test
    fun parse_emptyString_returnsEmptyAnnotatedString() {
        val result = parser.parse("")
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun parse_headings_extractsHeaderTextProperly() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
        """.trimIndent()

        val result = parser.parse(markdown)
        assertTrue(result.text.contains("Heading 1"))
        assertTrue(result.text.contains("Heading 2"))
        assertTrue(result.text.contains("Heading 3"))
        assertFalse(result.text.startsWith("#"))
    }

    @Test
    fun parse_inlineStyles_stripsMarkersAndAppliesFormatting() {
        val markdown = "This is **bold** text and *italic* and ~~strike~~ with `code`."
        val result = parser.parse(markdown)

        assertTrue(result.text.contains("bold"))
        assertTrue(result.text.contains("italic"))
        assertTrue(result.text.contains("strike"))
        assertTrue(result.text.contains("code"))
        assertFalse(result.text.contains("**"))
        assertFalse(result.text.contains("~~"))
        assertFalse(result.text.contains("`"))
    }

    @Test
    fun parse_links_extractsTextAndAnnotation() {
        val markdown = "Check out [Google](https://google.com) for searching."
        val result = parser.parse(markdown)

        assertTrue(result.text.contains("Google"))
        assertFalse(result.text.contains("[Google]"))
        assertFalse(result.text.contains("(https://google.com)"))

        val annotations = result.getStringAnnotations("URL", 0, result.length)
        assertEquals(1, annotations.size)
        assertEquals("https://google.com", annotations.first().item)
    }

    @Test
    fun parse_taskLists_convertsToCheckboxSymbols() {
        val markdown = """
            - [ ] Pending task
            - [x] Completed task
        """.trimIndent()

        val result = parser.parse(markdown)
        assertTrue(result.text.contains("☐ Pending task"))
        assertTrue(result.text.contains("☑ Completed task"))
    }

    @Test
    fun parse_codeBlock_preservesCodeContent() {
        val markdown = """
            ```kotlin
            fun main() {
                println("Hello World")
            }
            ```
        """.trimIndent()

        val result = parser.parse(markdown)
        assertTrue(result.text.contains("println(\"Hello World\")"))
        assertFalse(result.text.contains("```kotlin"))
    }

    @Test
    fun parseToAnnotatedString_largeFile_runsAsynchronouslyWithoutTimeout() = runTest(testDispatcher) {
        val largeBuilder = StringBuilder()
        for (i in 1..8000) {
            largeBuilder.append("## Header $i\n")
            largeBuilder.append("Item **bold $i** with `code $i` and [link $i](https://example.com/$i).\n")
            largeBuilder.append("- [ ] Task $i\n")
            largeBuilder.append("> Quote $i\n\n")
        }
        val largeMarkdown = largeBuilder.toString()
        assertTrue(largeMarkdown.length > 500 * 1024)

        val result = parser.parseToAnnotatedString(largeMarkdown)
        assertTrue(result.text.isNotEmpty())
    }

    @Test
    fun renderToHtml_convertsBasicElements() {
        val markdown = "# Title\n> Quote\n- List Item"
        val html = parser.renderToHtml(markdown)

        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<blockquote>Quote</blockquote>"))
        assertTrue(html.contains("<li>List Item</li>"))
    }
}
