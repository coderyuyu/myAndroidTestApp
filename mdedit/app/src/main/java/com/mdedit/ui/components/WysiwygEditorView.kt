package com.mdedit.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mdedit.domain.model.EditorFormatState
import org.json.JSONObject

class WysiwygEditorController {
    internal var webView: WebView? = null
    internal var isEditorReady: Boolean = false
    private var pendingMarkdown: String? = null

    fun setMarkdown(markdown: String) {
        if (isEditorReady) {
            val escaped = JSONObject.quote(markdown)
            webView?.post {
                webView?.evaluateJavascript("window.EditorAPI && window.EditorAPI.setMarkdown($escaped);", null)
            }
        } else {
            pendingMarkdown = markdown
        }
    }

    fun executeAction(action: ToolbarAction) {
        val jsFunction = when (action) {
            ToolbarAction.BOLD -> "formatBold()"
            ToolbarAction.ITALIC -> "formatItalic()"
            ToolbarAction.STRIKE -> "formatStrike()"
            ToolbarAction.H1 -> "formatHeader(1)"
            ToolbarAction.H2 -> "formatHeader(2)"
            ToolbarAction.H3 -> "formatHeader(3)"
            ToolbarAction.BULLET_LIST -> "formatBulletList()"
            ToolbarAction.NUMBERED_LIST -> "formatNumberedList()"
            ToolbarAction.CHECKLIST -> "formatChecklist()"
            ToolbarAction.BLOCKQUOTE -> "formatBlockquote()"
            ToolbarAction.CODE_BLOCK -> "formatCodeBlock()"
            ToolbarAction.HORIZONTAL_RULE -> "formatHorizontalRule()"
            ToolbarAction.UNDO -> "undo()"
            ToolbarAction.REDO -> "redo()"
            ToolbarAction.CLEAR_FORMAT -> "clearFormatting()"
        }

        webView?.post {
            webView?.evaluateJavascript("window.EditorAPI && window.EditorAPI.$jsFunction;", null)
        }
    }

    fun syncTheme(bgColor: String, textColor: String, accentColor: String, isDark: Boolean) {
        webView?.post {
            webView?.evaluateJavascript(
                "window.EditorAPI && window.EditorAPI.setTheme('$bgColor', '$textColor', '$accentColor', $isDark);",
                null
            )
        }
    }

    internal fun onReady() {
        isEditorReady = true
        pendingMarkdown?.let {
            setMarkdown(it)
            pendingMarkdown = null
        }
    }
}

@Composable
fun rememberWysiwygEditorController(): WysiwygEditorController {
    return remember { WysiwygEditorController() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WysiwygEditorView(
    controller: WysiwygEditorController,
    onContentChanged: (String) -> Unit,
    onFormatStateChanged: (EditorFormatState) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bgColorHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.background.toArgb())
    val textColorHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.onBackground.toArgb())
    val accentColorHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.primary.toArgb())

    LaunchedEffect(isDark, bgColorHex, textColorHex, accentColorHex) {
        controller.syncTheme(bgColorHex, textColorHex, accentColorHex, isDark)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    allowFileAccess = true
                }
                setBackgroundColor(0) // Transparent background
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        controller.syncTheme(bgColorHex, textColorHex, accentColorHex, isDark)
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onContentChanged(markdown: String) {
                        post { onContentChanged(markdown) }
                    }

                    @JavascriptInterface
                    fun onSelectionChanged(json: String) {
                        post {
                            runCatching {
                                val obj = JSONObject(json)
                                val state = EditorFormatState(
                                    isBold = obj.optBoolean("isBold", false),
                                    isItalic = obj.optBoolean("isItalic", false),
                                    isStrike = obj.optBoolean("isStrike", false),
                                    isBulletList = obj.optBoolean("isBulletList", false),
                                    isNumberedList = obj.optBoolean("isNumberedList", false),
                                    headerLevel = obj.optInt("headerLevel", 0),
                                    isBlockquote = obj.optBoolean("isBlockquote", false),
                                    isCodeBlock = obj.optBoolean("isCodeBlock", false)
                                )
                                onFormatStateChanged(state)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onEditorReady() {
                        post {
                            controller.onReady()
                            controller.syncTheme(bgColorHex, textColorHex, accentColorHex, isDark)
                        }
                    }
                }, "AndroidBridge")

                controller.webView = this
                loadUrl("file:///android_asset/editor/index.html")
            }
        },
        update = {
            controller.webView = it
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
            controller.isEditorReady = false
        }
    }
}
