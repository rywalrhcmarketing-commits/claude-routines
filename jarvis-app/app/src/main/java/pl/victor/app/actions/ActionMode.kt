package pl.victor.app.actions

/**
 * Tryb wykonywania akcji.
 *
 * - SAFE: otwiera zewnętrzną apkę (Spotify, Dialer, Gmail...). Bez dangerous permissions.
 * - DIRECT: wykonuje akcję bezpośrednio w naszej apce po potwierdzeniu dialogiem.
 *   Wymaga SEND_SMS, CALL_PHONE, READ_CONTACTS.
 */
enum class ActionMode(val displayName: String, val description: String) {
    SAFE(
        "Bezpieczny (Intent)",
        "Otwiera zewnętrzną apkę (Dialer, SMS, Gmail). " +
                "Bez dangerous permissions. Zawsze możesz anulować w ostatniej chwili."
    ),
    DIRECT(
        "Szybki (bezpośredni)",
        "Wysyła SMS / dzwoni bezpośrednio po potwierdzeniu w naszej apce. " +
                "Wymaga zgody na uprawnienia. Szybsze ale mniej kontroli."
    );

    companion object {
        fun fromName(name: String?): ActionMode =
            values().find { it.name == name } ?: SAFE
    }
}

/**
 * Rezultat z informacją czy wymaga potwierdzenia.
 */
sealed class ActionConfirmation {
    /** Akcja bezpieczna - wykonaj bez potwierdzenia */
    object NotRequired : ActionConfirmation()
    /** Wymaga potwierdzenia - pokaż dialog z pytaniem */
    data class Required(
        val title: String,
        val message: String,
        val confirmText: String = "OK",
        val cancelText: String = "Anuluj"
    ) : ActionConfirmation()
}
