package pl.jarvis.app.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Pobiera treść strony WWW dla URL wykrytego w QR kodzie.
 *
 * Pipeline:
 * 1. HTTP GET na URL
 * 2. Parsowanie HTML (regex na tekst)
 * 3. Limit do ~5000 znaków
 * 4. Zwrócenie plain text
 *
 * Prosty parser - nie wspiera JS / SPA. Wystarczy dla:
 * - Stron informacyjnych (menu restauracji, ogłoszenia)
 * - Wiki, artykuły
 * - Wizytówki
 */
class WebContentFetcher {

    private val tag = "WebContentFetcher"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Pobiera treść strony jako plain text.
     * Zwraca null jeśli URL nieprawidłowy lub strona nieosiągalna.
     */
    suspend fun fetch(url: String, maxChars: Int = 5000): WebContent? = withContext(Dispatchers.IO) {
        if (!isValidUrl(url)) {
            Log.w(tag, "Invalid URL: $url")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Jarvis/1.0 (HeyCyan Glasses)")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "HTTP ${response.code} for $url")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val contentType = response.header("Content-Type") ?: ""

                // Tylko HTML
                if (!contentType.contains("html", ignoreCase = true) &&
                    !contentType.contains("xml", ignoreCase = true)) {
                    Log.w(tag, "Non-HTML content: $contentType")
                    return@withContext WebContent(
                        url = url,
                        title = "Nieobsługiwany typ: $contentType",
                        text = "",
                        description = ""
                    )
                }

                parseHtml(body, url, maxChars)
            }
        } catch (e: Exception) {
            Log.e(tag, "Fetch failed for $url", e)
            null
        }
    }

    /**
     * Parsuje HTML do plain textu.
     * Usuwa tagi, dekoduje encje, normalizuje białe znaki.
     */
    private fun parseHtml(html: String, url: String, maxChars: Int): WebContent {
        // Wyciągnij tytuł
        val titlePattern = Pattern.compile("""<title[^>]*>(.*?)</title>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val titleMatcher = titlePattern.matcher(html)
        val title = if (titleMatcher.find()) {
            stripHtml(titleMatcher.group(1) ?: "").trim()
        } else {
            "Brak tytułu"
        }

        // Wyciągnij meta description
        val descPattern = Pattern.compile(
            """<meta\s+(?:name|property)=["']description["']\s+content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val descMatcher = descPattern.matcher(html)
        val description = if (descMatcher.find()) {
            decodeEntities(descMatcher.group(1) ?: "").trim()
        } else {
            ""
        }

        // Usuń style i skrypty
        var cleaned = html
            .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<head[^>]*>.*?</head>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<noscript[^>]*>.*?</noscript>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<footer[^>]*>.*?</footer>""", RegexOption.DOT_MATCHES_ALL), " ")

        // Zamień block elementy na newline
        cleaned = cleaned.replace(Regex("""<(?:p|div|br|h[1-6]|li|tr|td|th|article|section|header)[^>]*>"""), "\n")

        // Wyciągnij tekst (strip wszystkie tagi)
        cleaned = stripHtml(cleaned)

        // Normalizuj whitespace
        cleaned = cleaned.replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n\s*\n\s*\n+"""), "\n\n")
            .trim()

        // Limit
        if (cleaned.length > maxChars) {
            cleaned = cleaned.take(maxChars) + "..."
        }

        return WebContent(
            url = url,
            title = title,
            text = cleaned,
            description = description
        )
    }

    /**
     * Usuwa tagi HTML (uproszczone).
     */
    private fun stripHtml(html: String): String {
        var s = html.replace(Regex("""<[^>]+>"""), " ")
        s = decodeEntities(s)
        return s
    }

    /**
     * Dekoduje podstawowe encje HTML.
     */
    private fun decodeEntities(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&hellip;", "...")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&euro;", "€")
            .replace("&hellip;", "...")
    }

    /**
     * Waliduje URL.
     */
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
               url.startsWith("https://", ignoreCase = true)
    }
}

data class WebContent(
    val url: String,
    val title: String,
    val text: String,
    val description: String
) {
    /** Ile znaków razem */
    fun totalChars(): Int = title.length + description.length + text.length

    /**
     * Kompaktowa reprezentacja do wysłania do AI.
     */
    fun toCompactString(): String = buildString {
        append("URL: $url\n")
        if (title.isNotBlank()) append("Tytuł: $title\n")
        if (description.isNotBlank()) append("Opis: $description\n")
        append("\nTreść:\n$text")
    }
}
