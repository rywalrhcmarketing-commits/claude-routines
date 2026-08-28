package pl.jarvis.app.vision

/**
 * Parser wizytówek vCard z kodów QR.
 *
 * Kody QR na wizytówkach i identyfikatorach niosą zwykle format vCard
 * (`BEGIN:VCARD ... END:VCARD`) albo prostszy MeCard używany w Japonii.
 * Obsługiwane są oba, bo skaner ML Kit zwraca surową treść kodu.
 *
 * Parser jest celowo pobłażliwy: pomija nieznane pola zamiast się wywracać,
 * bo wizytówki w praktyce bywają niezgodne ze specyfikacją.
 */
object VCardParser {

    /** Czy treść kodu wygląda na wizytówkę. */
    fun looksLikeContact(raw: String): Boolean {
        val trimmed = raw.trimStart()
        return trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) ||
            trimmed.startsWith("MECARD:", ignoreCase = true)
    }

    /**
     * Parsuje treść kodu QR na kontakt.
     * @return kontakt albo `null` gdy treść nie jest wizytówką lub nie ma nazwy
     */
    fun parse(raw: String): ContactCard? {
        val trimmed = raw.trimStart()
        return when {
            trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseVCard(trimmed)
            trimmed.startsWith("MECARD:", ignoreCase = true) -> parseMeCard(trimmed)
            else -> null
        }
    }

    private fun parseVCard(raw: String): ContactCard? {
        var name: String? = null
        var org: String? = null
        var title: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var url: String? = null
        var address: String? = null

        // Linie zawinięte w vCard zaczynają się od spacji lub tabulatora.
        val lines = unfoldLines(raw)

        for (line in lines) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            // Nazwa pola może mieć parametry: "TEL;TYPE=CELL"
            val field = line.substring(0, separator).uppercase()
            val value = unescape(line.substring(separator + 1).trim())
            if (value.isEmpty()) continue

            when {
                field == "FN" -> name = value
                field.startsWith("N") && !field.startsWith("NOTE") && name == null ->
                    // Format N: nazwisko;imię;drugie;prefiks;sufiks
                    name = value.split(';')
                        .filter { it.isNotBlank() }
                        .asReversed()
                        .joinToString(" ")
                        .trim()
                field.startsWith("ORG") -> org = value.replace(";", ", ").trim(',', ' ')
                field.startsWith("TITLE") || field.startsWith("ROLE") -> title = value
                field.startsWith("TEL") -> phones.add(value)
                field.startsWith("EMAIL") -> emails.add(value)
                field.startsWith("URL") -> url = value
                field.startsWith("ADR") -> address = value.split(';')
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
            }
        }

        val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
        return ContactCard(resolvedName, org, title, phones, emails, url, address)
    }

    private fun parseMeCard(raw: String): ContactCard? {
        val body = raw.removePrefix("MECARD:").removePrefix("mecard:").removeSuffix(";;")
        var name: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var url: String? = null
        var address: String? = null
        var org: String? = null

        for (part in body.split(';')) {
            val separator = part.indexOf(':')
            if (separator <= 0) continue
            val field = part.substring(0, separator).uppercase()
            val value = unescape(part.substring(separator + 1).trim())
            if (value.isEmpty()) continue

            when (field) {
                "N" -> name = value.split(',').asReversed().joinToString(" ").trim()
                "TEL" -> phones.add(value)
                "EMAIL" -> emails.add(value)
                "URL" -> url = value
                "ADR" -> address = value.replace(",", ", ")
                "ORG" -> org = value
            }
        }

        val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
        return ContactCard(resolvedName, org, null, phones, emails, url, address)
    }

    /** Skleja linie zawinięte zgodnie z RFC 6350 (kontynuacja zaczyna się białym znakiem). */
    private fun unfoldLines(raw: String): List<String> {
        val result = mutableListOf<String>()
        for (line in raw.lines()) {
            if (line.isEmpty()) continue
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                // RFC 6350: usuwa się dokładnie jeden znak białego, reszta należy
                // do wartości. trimStart() zjadłby spację rozdzielającą wyrazy.
                result[result.lastIndex] = result.last() + line.substring(1)
            } else {
                result.add(line.trim())
            }
        }
        return result
    }

    /** Odwraca sekwencje ucieczki vCard (`\,` `\;` `\n`). */
    private fun unescape(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

/** Kontakt odczytany z kodu QR. */
data class ContactCard(
    val name: String,
    val organization: String? = null,
    val title: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val url: String? = null,
    val address: String? = null
) {
    /** Krótki opis do wypowiedzenia przez TTS. */
    fun spoken(): String = buildString {
        append(name)
        title?.let { append(", ").append(it) }
        organization?.let { append(", ").append(it) }
        phones.firstOrNull()?.let { append(", telefon ").append(it) }
        emails.firstOrNull()?.let { append(", e-mail ").append(it) }
    }

    /** Kontekst dla modelu AI. */
    fun toPromptContext(): String = buildString {
        appendLine("Wizytówka odczytana z kodu QR:")
        appendLine("- Imię i nazwisko: $name")
        organization?.let { appendLine("- Firma: $it") }
        title?.let { appendLine("- Stanowisko: $it") }
        if (phones.isNotEmpty()) appendLine("- Telefon: ${phones.joinToString(", ")}")
        if (emails.isNotEmpty()) appendLine("- E-mail: ${emails.joinToString(", ")}")
        url?.let { appendLine("- Strona: $it") }
        address?.let { appendLine("- Adres: $it") }
    }
}
