package com.dir2gpx.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Expands shortened Google Maps URLs (e.g. `maps.app.goo.gl/xxx`, `goo.gl/maps/xxx`)
 * to their canonical directions form by following HTTP redirects, resolving Android
 * intent deep links, and inspecting HTML document headers.
 */
object UrlExpanderService {

    private const val MAX_REDIRECTS = 10

    // Realistic Android Chrome User-Agent so Google services return canonical web redirects
    // rather than blocking bot requests or showing generic consent walls
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Expands a potentially shortened URL by following redirect chains.
     *
     * @param inputUrl The input URL (e.g., `maps.app.goo.gl/...` or `google.com/maps/dir/...`).
     * @return The final canonical URL after all redirects.
     */
    suspend fun expand(inputUrl: String): String = withContext(Dispatchers.IO) {
        var currentUrl = cleanInput(inputUrl)
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            // Check if current URL is already an intent or app URI
            val unwrappedIntent = extractFromIntentUri(currentUrl)
            if (unwrappedIntent != null && unwrappedIntent != currentUrl) {
                currentUrl = unwrappedIntent
                redirectCount++
                continue
            }

            // If we already have a full canonical /maps/dir/ URL with coordinates, we can stop early
            if (isCanonicalDirectionUrl(currentUrl)) {
                return@withContext currentUrl
            }

            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            var hopResolved = false
            var lastError: Exception? = null

            for (attempt in 1..3) {
                try {
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        if (code in 300..399) {
                            val location = response.header("Location")
                                ?: return@withContext currentUrl

                            // Resolve relative redirects against current URL
                            val nextUrl = when {
                                location.startsWith("http://") || location.startsWith("https://") -> location
                                location.startsWith("intent://") || location.startsWith("android-app://") ->
                                    extractFromIntentUri(location) ?: location
                                else -> {
                                    val base = currentUrl.substringBefore("?").substringBeforeLast("/")
                                    "$base/${location.removePrefix("/")}"
                                }
                            }

                            currentUrl = nextUrl
                            redirectCount++
                            hopResolved = true
                        } else if (code in 200..299) {
                            // Check for meta refresh, og:url, canonical link, or JS redirects in the body
                            val body = response.body?.string() ?: return@withContext currentUrl

                            val extractedUrl = extractTargetUrlFromBody(body)
                            if (extractedUrl != null && extractedUrl != currentUrl) {
                                currentUrl = extractedUrl
                                redirectCount++
                                hopResolved = true
                            } else {
                                return@withContext currentUrl
                            }
                        } else {
                            // Non-2xx/3xx code, return the best URL we got so far
                            return@withContext currentUrl
                        }
                    }
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) {
                        kotlinx.coroutines.delay(600L * attempt)
                    }
                }
            }

            if (!hopResolved && lastError != null) {
                // If network fails during redirect hops, return the last resolved URL
                if (redirectCount > 0) {
                    return@withContext currentUrl
                }
                throw IOException("Failed to resolve URL: ${lastError.message}", lastError)
            }
        }

        currentUrl
    }

    /**
     * Extracts an HTTPS URL from an Android intent scheme or app URI.
     * E.g.: `android-app://com.google.android.apps.maps/https/www.google.com/maps/dir/...`
     * or `intent://maps.google.com/maps/dir/...?#Intent;scheme=https;...`
     */
    fun extractFromIntentUri(uri: String): String? {
        if (uri.startsWith("android-app://")) {
            val httpsIndex = uri.indexOf("https/")
            if (httpsIndex != -1) {
                return "https://" + uri.substring(httpsIndex + 6)
            }
            val httpIndex = uri.indexOf("http/")
            if (httpIndex != -1) {
                return "http://" + uri.substring(httpIndex + 5)
            }
        }

        if (uri.startsWith("intent://")) {
            val stripped = uri.removePrefix("intent://")
            val hostAndPath = stripped.substringBefore("#Intent")
            return "https://$hostAndPath"
        }

        return null
    }

    /**
     * Inspects HTML body for redirection metadata or canonical Google Maps links.
     */
    private fun extractTargetUrlFromBody(html: String): String? {
        // 1. Meta refresh: <meta http-equiv="refresh" content="0;url=https://...">
        val metaRefreshRegex = Regex(
            """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]+content\s*=\s*["'][^"']*url\s*=\s*([^"'\s>]+)""",
            RegexOption.IGNORE_CASE
        )
        metaRefreshRegex.find(html)?.let { return it.groupValues[1].trim() }

        // 2. OpenGraph URL: <meta property="og:url" content="https://...">
        val ogUrlRegex = Regex(
            """<meta[^>]+property\s*=\s*["']og:url["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        ogUrlRegex.find(html)?.let {
            val url = it.groupValues[1].trim()
            if (url.contains("/maps")) return url
        }

        // 3. Canonical link: <link rel="canonical" href="https://...">
        val canonicalRegex = Regex(
            """<link[^>]+rel\s*=\s*["']canonical["'][^>]+href\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        canonicalRegex.find(html)?.let {
            val url = it.groupValues[1].trim()
            if (url.contains("/maps")) return url
        }

        // 4. Embedded Google Maps Directions URL
        val mapsDirRegex = Regex(
            """https?://(?:www\.)?google\.[a-z.]+/maps/dir/[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        )
        mapsDirRegex.find(html)?.let { return it.value }

        // 5. JavaScript window.location redirect
        val jsRegex = Regex(
            """window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        jsRegex.find(html)?.let { return it.groupValues[1].trim() }

        return null
    }

    /**
     * Checks if a URL already looks like a canonical directions URL.
     */
    private fun isCanonicalDirectionUrl(url: String): Boolean {
        return url.contains("/maps/dir/") &&
            (url.contains(",") || url.contains("data=") || url.contains("origin="))
    }

    /**
     * Normalizes the input URL.
     */
    private fun cleanInput(input: String): String {
        var trimmed = input.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }
}
