package com.mdedit.data.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast, lightweight Markdown parser and renderer.
 * Converts raw Markdown text into a styled Jetpack Compose [AnnotatedString]
 * or rendered HTML without blocking the main UI thread.
 */
@Singleton
class MarkdownParser(
    private val defaultDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor() : this(Dispatchers.Default)

    /**
     * Parses markdown text into an [AnnotatedString] on a background dispatcher.
     */
    suspend fun parseToAnnotatedString(markdown: String): AnnotatedString {
        if (markdown.isEmpty()) return AnnotatedString("")
        return withContext(defaultDispatcher) {
            parse(markdown)
        }
    }

    /**
     * Synchronous parser converting raw markdown to [AnnotatedString].
     */
    fun parse(markdown: String): AnnotatedString {
        if (markdown.isEmpty()) return AnnotatedString("")

        return buildAnnotatedString {
            val lines = markdown.lines()
            var inCodeBlock = false
            val codeBlockBuffer = StringBuilder()

            for (i in lines.indices) {
                val line = lines[i]

                // Check for fenced code block toggle
                if (line.trimStart().startsWith("```")) {
                    if (inCodeBlock) {
                        // End of code block
                        val codeContent = codeBlockBuffer.toString()
                        val start = length
                        append(codeContent)
                        addStyle(
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                background = Color(0x1F888888)
                            ),
                            start = start,
                            end = length
                        )
                        if (codeContent.isNotEmpty() && !codeContent.endsWith("\n")) {
                            append("\n")
                        }
                        codeBlockBuffer.clear()
                        inCodeBlock = false
                    } else {
                        // Start of code block
                        inCodeBlock = true
                        codeBlockBuffer.clear()
                    }
                    continue
                }

                if (inCodeBlock) {
                    codeBlockBuffer.append(line).append("\n")
                    continue
                }

                // Horizontal Rule
                val trimmed = line.trim()
                if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                    val start = length
                    append("────────────────────────────────────────\n")
                    addStyle(
                        style = SpanStyle(color = Color(0x66888888), fontSize = 10.sp),
                        start = start,
                        end = length
                    )
                    continue
                }

                // Headings (#, ##, ###, ####, #####, ######)
                if (line.startsWith("#")) {
                    val hashCount = line.takeWhile { it == '#' }.length
                    if (hashCount in 1..6 && line.length > hashCount && line[hashCount] == ' ') {
                        val headerText = line.substring(hashCount + 1).trim()
                        val fontSize = when (hashCount) {
                            1 -> 24.sp
                            2 -> 20.sp
                            3 -> 17.sp
                            4 -> 15.sp
                            else -> 14.sp
                        }
                        val start = length
                        appendLineWithInlineStyles(this, headerText)
                        addStyle(
                            style = SpanStyle(
                                fontSize = fontSize,
                                fontWeight = FontWeight.Bold
                            ),
                            start = start,
                            end = length
                        )
                        continue
                    }
                }

                // Blockquote (>)
                if (line.startsWith("> ") || line == ">") {
                    val quoteText = if (line.length > 2) line.substring(2) else ""
                    val start = length
                    append("▍ ")
                    addStyle(
                        style = SpanStyle(color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold),
                        start = start,
                        end = length
                    )
                    val quoteStart = length
                    appendLineWithInlineStyles(this, quoteText)
                    addStyle(
                        style = SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = Color(0xCCAAAAAA)
                        ),
                        start = quoteStart,
                        end = length
                    )
                    continue
                }

                // Task list item: - [ ] or - [x]
                if (line.startsWith("- [ ] ") || line.startsWith("* [ ] ")) {
                    val text = line.substring(6)
                    append("☐ ")
                    appendLineWithInlineStyles(this, text)
                    continue
                }
                if (line.startsWith("- [x] ") || line.startsWith("- [X] ") ||
                    line.startsWith("* [x] ") || line.startsWith("* [X] ")
                ) {
                    val text = line.substring(6)
                    val start = length
                    append("☑ ")
                    appendLineWithInlineStyles(this, text)
                    addStyle(
                        style = SpanStyle(
                            textDecoration = TextDecoration.LineThrough,
                            color = Color(0x99888888)
                        ),
                        start = start,
                        end = length
                    )
                    continue
                }

                // Unordered list item: - , * , +
                if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                    append("• ")
                    appendLineWithInlineStyles(this, line.substring(2))
                    continue
                }

                // Ordered list item: 1. , 2. etc.
                val orderedMatch = ORDERED_LIST_REGEX.find(line)
                if (orderedMatch != null) {
                    val prefix = orderedMatch.value
                    append(prefix)
                    appendLineWithInlineStyles(this, line.substring(prefix.length))
                    continue
                }

                // Normal paragraph line
                appendLineWithInlineStyles(this, line)
            }

            // Flush any unclosed code block safely
            if (inCodeBlock && codeBlockBuffer.isNotEmpty()) {
                val start = length
                append(codeBlockBuffer.toString())
                addStyle(
                    style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = Color(0x1F888888)
                    ),
                    start = start,
                    end = length
                )
            }
        }
    }

    /**
     * Parses inline styles: bold (**text**), italic (*text*), strikethrough (~~text~~),
     * inline code (`code`), and links ([label](url)).
     */
    private fun appendLineWithInlineStyles(builder: AnnotatedString.Builder, line: String) {
        if (line.isEmpty()) {
            builder.append("\n")
            return
        }

        var i = 0
        val len = line.length

        while (i < len) {
            // Inline code: `...`
            if (line[i] == '`') {
                val closing = line.indexOf('`', i + 1)
                if (closing != -1) {
                    val code = line.substring(i + 1, closing)
                    val start = builder.length
                    builder.append(code)
                    builder.addStyle(
                        style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = Color(0x22888888)
                        ),
                        start = start,
                        end = builder.length
                    )
                    i = closing + 1
                    continue
                }
            }

            // Strikethrough: ~~...~~
            if (i + 1 < len && line[i] == '~' && line[i + 1] == '~') {
                val closing = line.indexOf("~~", i + 2)
                if (closing != -1) {
                    val text = line.substring(i + 2, closing)
                    val start = builder.length
                    builder.append(text)
                    builder.addStyle(
                        style = SpanStyle(textDecoration = TextDecoration.LineThrough),
                        start = start,
                        end = builder.length
                    )
                    i = closing + 2
                    continue
                }
            }

            // Bold: **...** or __...__
            if (i + 1 < len && ((line[i] == '*' && line[i + 1] == '*') || (line[i] == '_' && line[i + 1] == '_'))) {
                val marker = line.substring(i, i + 2)
                val closing = line.indexOf(marker, i + 2)
                if (closing != -1) {
                    val text = line.substring(i + 2, closing)
                    val start = builder.length
                    builder.append(text)
                    builder.addStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold),
                        start = start,
                        end = builder.length
                    )
                    i = closing + 2
                    continue
                }
            }

            // Italic: *...* or _..._
            if (line[i] == '*' || line[i] == '_') {
                val marker = line[i]
                val closing = line.indexOf(marker, i + 1)
                if (closing != -1 && closing > i + 1) {
                    val text = line.substring(i + 1, closing)
                    val start = builder.length
                    builder.append(text)
                    builder.addStyle(
                        style = SpanStyle(fontStyle = FontStyle.Italic),
                        start = start,
                        end = builder.length
                    )
                    i = closing + 1
                    continue
                }
            }

            // Link: [label](url)
            if (line[i] == '[') {
                val closeBracket = line.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < len && line[closeBracket + 1] == '(') {
                    val closeParen = line.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val label = line.substring(i + 1, closeBracket)
                        val url = line.substring(closeBracket + 2, closeParen)
                        val start = builder.length
                        builder.append(label)
                        builder.addStyle(
                            style = SpanStyle(
                                color = Color(0xFF06B6D4),
                                textDecoration = TextDecoration.Underline
                            ),
                            start = start,
                            end = builder.length
                        )
                        builder.addStringAnnotation(
                            tag = "URL",
                            annotation = url,
                            start = start,
                            end = builder.length
                        )
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Normal character
            builder.append(line[i])
            i++
        }
        builder.append("\n")
    }

    /**
     * Converts markdown to basic HTML for preview or export.
     */
    fun renderToHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCode = false

        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    sb.append("</code></pre>\n")
                    inCode = false
                } else {
                    sb.append("<pre><code>")
                    inCode = true
                }
                continue
            }
            if (inCode) {
                sb.append(escapeHtml(line)).append("\n")
                continue
            }

            if (line.startsWith("# ")) {
                sb.append("<h1>").append(escapeHtml(line.substring(2))).append("</h1>\n")
            } else if (line.startsWith("## ")) {
                sb.append("<h2>").append(escapeHtml(line.substring(3))).append("</h2>\n")
            } else if (line.startsWith("### ")) {
                sb.append("<h3>").append(escapeHtml(line.substring(4))).append("</h3>\n")
            } else if (line.startsWith("> ")) {
                sb.append("<blockquote>").append(escapeHtml(line.substring(2))).append("</blockquote>\n")
            } else if (line.startsWith("- ")) {
                sb.append("<li>").append(escapeHtml(line.substring(2))).append("</li>\n")
            } else if (line.trim() == "---") {
                sb.append("<hr/>\n")
            } else if (line.isNotBlank()) {
                sb.append("<p>").append(escapeHtml(line)).append("</p>\n")
            }
        }
        if (inCode) {
            sb.append("</code></pre>\n")
        }
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private val ORDERED_LIST_REGEX = Regex("""^\s*\d+\.\s+""")
    }
}
