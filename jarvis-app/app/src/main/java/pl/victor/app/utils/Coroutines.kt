package pl.victor.app.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Handler, który **loguje zamiast wywracać aplikację**.
 *
 * ## Po co
 * `SupervisorJob` chroni rodzeństwo korutyn przed wzajemnym ubijaniem, ale NIE
 * przechwytuje wyjątku - ten leci dalej, do systemowego handlera, czyli kończy
 * proces. W długo żyjących komponentach (BLE, dźwięk, tryb konwersacyjny)
 * oznacza to, że pojedynczy nieprzewidziany błąd - zerwane połączenie, wyjątek
 * z SDK producenta, wyścig przy rozłączaniu - zamyka całą aplikację.
 *
 * Dla asystenta noszonego na głowie to najgorszy możliwy wynik: telefon jest
 * w kieszeni, użytkownik nie widzi ekranu i dowiaduje się o awarii dopiero po
 * tym, że nic nie odpowiada.
 *
 * ## Czego to NIE robi
 * Nie ukrywa błędów - każdy ląduje w dzienniku z pełnym stosem. Nie łapie też
 * anulowania: [CancellationException] to normalne przerwanie pracy, nie awaria.
 * Komponenty, które potrafią pokazać błąd użytkownikowi (jak orkiestrator),
 * mają własne handlery i tego nie używają.
 */
fun loggingExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        if (error is CancellationException) return@CoroutineExceptionHandler
        Log.e(tag, "Nieobsłużony błąd w korutynie - aplikacja działa dalej", error)
    }
