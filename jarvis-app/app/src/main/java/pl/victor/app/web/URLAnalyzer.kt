package pl.victor.app.web

import android.util.Log
import pl.victor.app.vision.ScannedCode

/**
 * Analizator URLi z kodów QR.
 *
 * Workflow:
 * 1. Z QR skanowania dostajemy ScannedCode
 * 2. Jeśli to URL → fetch content
 * 3. Wygeneruj "analizę" - tekst dla AI jako kontekst
 *
 * Przykłady użycia:
 * - QR w restauracji z menu → "Co jest w menu?"
 * - QR na wizytówce → "Powiedz o tej osobie"
 * - QR na plakacie (URL do eventu) → "Co to za wydarzenie?"
 */
class URLAnalyzer {

    private val tag = "URLAnalyzer"

    /**
     * Wyciąga URL z listy zeskanowanych kodów.
     */
    fun extractUrls(codes: List<ScannedCode>): List<String> {
        return codes.mapNotNull { code ->
            when {
                code.url != null -> code.url
                code.type == "URL" -> code.rawValue
                code.rawValue.startsWith("http://", ignoreCase = true) -> code.rawValue
                code.rawValue.startsWith("https://", ignoreCase = true) -> code.rawValue
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    /**
     * Fetch i zwraca treść dla pierwszego URL.
     */
    suspend fun fetchFirstUrl(codes: List<ScannedCode>): WebContent? {
        val urls = extractUrls(codes)
        if (urls.isEmpty()) {
            Log.d(tag, "Brak URLi w zeskanowanych kodach")
            return null
        }
        val fetcher = WebContentFetcher()
        val content = fetcher.fetch(urls.first())
        Log.i(tag, "Fetched URL: ${urls.first()}, title: ${content?.title}")
        return content
    }

    /**
     * Generuje fragment promptu dla AI - kontekst o URL.
     */
    fun buildPromptContext(content: WebContent): String {
        return """
            |Użytkownik wskazał Ci stronę internetową (np. przez QR kod).
            |Przeczytaj poniższą treść i streść ją po polsku.
            |
            |${content.toCompactString()}
            |
            |WAŻNE: Jeśli pytanie użytkownika dotyczy tej strony - odpowiedz na podstawie treści.
            |Jeśli nie - powiedz czego dotyczy strona i zapytaj co user chce wiedzieć.
        """.trimMargin()
    }
}
