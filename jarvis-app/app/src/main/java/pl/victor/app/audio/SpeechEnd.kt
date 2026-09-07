package pl.victor.app.audio

/**
 * Kiedy wolno uznać, że użytkownik skończył mówić.
 *
 * ## Dlaczego to jest osobny plik
 * Bo to jedyne miejsce, w którym aplikacja decyduje o urwaniu nasłuchu - a od
 * czasu, gdy sami wysyłamy okularom komendę końca sesji, pomyłka tutaj
 * NAPRAWDĘ ucina pytanie w pół słowa. Wyjęte z [GlassesVoiceCapture] razem ze
 * stałymi, żeby dało się to sprawdzić testem bez okularów, BLE i Androida.
 */
object SpeechEnd {

    /**
     * Ile mowy musi przyjść, zanim cisza zacznie cokolwiek znaczyć.
     *
     * Poprzednio próg był w PAKIETACH (piętnaście) i to była przyczyna
     * zgłoszenia "z okularów wychodzi tylko urywek dźwięku 0,2-0,3 sekundy":
     * jeden pakiet to jedna ramka Opusa, czyli 20 ms, więc piętnaście pakietów
     * znaczyło 0,3 s. Krótki dźwięk na starcie plus sekunda ciszy wyglądały
     * wtedy jak skończona wypowiedź. Sekunda to najkrótsze sensowne pytanie;
     * poniżej niej cisza jest przerwą PRZED mówieniem, a nie po nim.
     */
    const val MIN_VOICED_MS = 1_000L

    /**
     * Zanim minie tyle czasu od startu, cisza NIE kończy tury.
     *
     * Między wybudzeniem a pierwszym słowem jest dźwięk wybudzenia, negocjacja
     * łącza SCO (na starszym Androidzie nawet kilka sekund) i moment na
     * zebranie myśli. Bez tego okna każdy dźwięk na starcie plus chwila ciszy
     * kończyły nasłuch, zanim użytkownik zaczął mówić.
     */
    const val MIN_LISTEN_MS = 2_500L

    /** Tyle ciszy w strumieniu znaczy "skończył mówić". */
    const val SILENCE_ENDS_SPEECH_MS = 1_200L

    /**
     * Dłuższa przerwa między pakietami to cisza, a nie mowa.
     *
     * Pakiety idą co 20 ms, więc 400 ms zostawia zapas na nierówności BLE, a
     * jednocześnie nie wlicza do "mowy" przerwy na oddech ani pauzy między
     * dźwiękiem wybudzenia a pytaniem.
     */
    const val VOICE_GAP_MS = 400L

    /**
     * Najdłuższa wypowiedź, na jaką czekamy.
     *
     * Nie po to, żeby ucinać zdania, tylko żeby tura nie stała, gdy okulary
     * nadają ciszę bez końca. Bez tego o zakończeniu decydował dopiero
     * piętnastosekundowy zegar rozpoznawania mowy.
     */
    const val MAX_SPEECH_MS = 12_000L

    /**
     * Czy cisza w strumieniu oznacza koniec wypowiedzi.
     *
     * @param voicedMs ile faktycznej mowy już przyszło (suma KRÓTKICH odstępów
     *   między pakietami - patrz [VOICE_GAP_MS])
     * @param sinceLastPacketMs jak dawno przyszedł ostatni pakiet; `-1`, gdy nie
     *   przyszedł jeszcze ani jeden
     * @param sinceStartMs ile minęło od rozpoczęcia nasłuchu
     */
    fun endsOnSilence(
        voicedMs: Long,
        sinceLastPacketMs: Long,
        sinceStartMs: Long,
        silenceMs: Long = SILENCE_ENDS_SPEECH_MS,
        minVoicedMs: Long = MIN_VOICED_MS,
        graceMs: Long = MIN_LISTEN_MS
    ): Boolean {
        // Brak pakietów to NIE cisza: okulary mogą w ogóle nie nadawać tym
        // kanałem, a wtedy jedynym sędzią jest rozpoznawanie mowy.
        if (sinceLastPacketMs < 0L) return false
        if (sinceStartMs < graceMs) return false
        if (voicedMs < minVoicedMs) return false
        return sinceLastPacketMs >= silenceMs
    }

    /** Ile z odstępu między pakietami liczy się jako mowa. */
    fun voicedGap(gapMs: Long): Long = if (gapMs in 0..VOICE_GAP_MS) gapMs else 0L
}
