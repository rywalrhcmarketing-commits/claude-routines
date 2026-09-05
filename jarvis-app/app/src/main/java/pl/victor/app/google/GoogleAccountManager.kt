package pl.victor.app.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Jeden wspólny login Google dla całej apki.
 *
 * Wszystkie usługi Google (Calendar, Gmail, w przyszłości inne) proszą o
 * dostęp w JEDNYM ekranie zgody - user loguje się raz i ma dostęp do
 * wszystkiego, zamiast osobnego logowania (i osobnego ekranu zgody) per
 * usługa. Dodanie nowej usługi Google = dopisz jej scope do listy w
 * `signInOptions` poniżej.
 *
 * Wymaga w Google Cloud Console (projekt OAuth apki):
 * - włączonego Calendar API i Gmail API
 * - scope'ów poniżej dodanych do OAuth consent screen
 * To jest konfiguracja po stronie Google, nie kodu - sam kod nie wystarczy.
 */
class GoogleAccountManager(private val context: Context) {

    private val tag = "GoogleAccountManager"

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            Scope(CalendarScopes.CALENDAR),
            Scope(CalendarScopes.CALENDAR_EVENTS),
            Scope(GmailScopes.GMAIL_READONLY),
            Scope(GmailScopes.GMAIL_SEND)
        )
        .build()

    private val signInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions)

    /**
     * Intent do uruchomienia flow logowania Google (jeden dla wszystkich usług).
     */
    fun getSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Czy user jest zalogowany I ma nadane WSZYSTKIE scope'y powyżej.
     *
     * Konto może być zalogowane jeszcze z czasów przed dodaniem Gmaila -
     * wtedy ma tylko Calendar scope. Traktujemy to jak brak logowania,
     * żeby wymusić ponowną zgodę zamiast dostać 403 przy pierwszym
     * wywołaniu Gmail API.
     */
    fun isSignedIn(): Boolean = getCurrentAccount() != null

    fun getCurrentAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, *signInOptions.scopeArray)) {
            account
        } else {
            null
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        signInClient.signOut()
        Log.i(tag, "Wylogowano z konta Google")
    }

    /**
     * Credential do zbudowania dowolnego klienta Google API (Calendar, Gmail, ...).
     * Zwraca `null` gdy user nie jest zalogowany (patrz [isSignedIn]).
     */
    fun getCredential(scopes: Collection<String>): GoogleAccountCredential? {
        val account = getCurrentAccount() ?: return null
        return GoogleAccountCredential
            .usingOAuth2(context, scopes)
            .setSelectedAccountName(account.email)
    }

    /**
     * Odczytuje wynik logowania z intencji zwróconej przez ekran wyboru konta.
     *
     * ## Dlaczego to musi być JEDNO miejsce
     * Logowanie da się uruchomić z dwóch ekranów - głównego i ustawień - a
     * obsługa wyniku była w każdym inna. W ustawieniach czytała błąd, na ekranie
     * głównym sprawdzała samo `resultCode` i milczała. To jest dokładnie ten
     * rodzaj różnicy, po którym "raz działa, raz nie": ta sama nieudana próba
     * kończyła się komunikatem albo ciszą, zależnie od tego, skąd się zaczęło.
     *
     * ## Czemu `resultCode` nie wystarczy
     * Google Sign-In sygnalizuje błąd WEWNĄTRZ intencji, a nie kodem wyniku.
     * `DEVELOPER_ERROR` (klient OAuth nieskonfigurowany dla tego pakietu i
     * odcisku SHA-1) wraca tak samo jak zwykłe cofnięcie się z ekranu wyboru
     * konta - z zewnątrz nie do odróżnienia, mimo że pierwsze to trwała usterka
     * konfiguracji, a drugie decyzja użytkownika.
     */
    fun handleSignInResult(data: Intent?): SignInOutcome {
        if (data == null) {
            return SignInOutcome.Cancelled
        }
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
                ?: return SignInOutcome.Failed(
                    "Google nie zwróciło konta. Spróbuj jeszcze raz."
                )
            val missing = missingScopes(account)
            if (missing.isNotEmpty()) {
                Log.w(tag, "Zalogowano, ale bez zgód: $missing")
                SignInOutcome.MissingConsent(account, missing)
            } else {
                Log.i(tag, "Konto Google połączone: ${account.email}")
                SignInOutcome.Success(account)
            }
        } catch (e: ApiException) {
            Log.e(tag, "Logowanie Google nie powiodło się (kod ${e.statusCode})", e)
            when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> SignInOutcome.Cancelled
                else -> SignInOutcome.Failed(describeStatus(e.statusCode))
            }
        }
    }

    /** Zgody, których konto NIE ma, opisane po ludzku. */
    fun missingScopes(account: GoogleSignInAccount): List<String> =
        signInOptions.scopeArray
            .filterNot { GoogleSignIn.hasPermissions(account, it) }
            .map { SCOPE_NAMES[it.scopeUri] ?: it.scopeUri }

    /** Co się stało przy logowaniu - w formie, którą da się pokazać człowiekowi. */
    sealed class SignInOutcome {
        data class Success(val account: GoogleSignInAccount) : SignInOutcome()

        /** Zalogowano, ale użytkownik nie dał wszystkich zgód. */
        data class MissingConsent(
            val account: GoogleSignInAccount,
            val missing: List<String>
        ) : SignInOutcome()

        object Cancelled : SignInOutcome()

        data class Failed(val message: String) : SignInOutcome()
    }

    companion object {
        /**
         * Opis kodu błędu logowania. Bez tego użytkownik widzi liczbę albo nic,
         * a to są przyczyny, z których każda wymaga czegoś zupełnie innego.
         */
        fun describeStatus(statusCode: Int): String = when (statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Klient OAuth nie jest skonfigurowany dla tej wersji aplikacji " +
                    "(nazwa pakietu + odcisk SHA-1 podpisu). Trzeba go dodać w " +
                    "Google Cloud Console - to konfiguracja po stronie Google, " +
                    "nie usterka telefonu."
            CommonStatusCodes.NETWORK_ERROR ->
                "Brak połączenia z siecią. Włącz internet i spróbuj ponownie."
            CommonStatusCodes.INTERNAL_ERROR ->
                "Usługi Google zgłosiły błąd wewnętrzny. Spróbuj za chwilę."
            CommonStatusCodes.INVALID_ACCOUNT ->
                "Wybrane konto jest niedostępne. Wybierz inne."
            CommonStatusCodes.SIGN_IN_REQUIRED ->
                "Konto wymaga ponownego zalogowania."
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Logowanie anulowane."
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS ->
                "Logowanie już trwa - poczekaj na ekran wyboru konta."
            GoogleSignInStatusCodes.SIGN_IN_FAILED ->
                "Logowanie nie powiodło się. Sprawdź, czy Usługi Google Play są " +
                    "aktualne."
            else -> "Logowanie nie powiodło się (kod $statusCode)."
        }

        /** Nazwy zgód po polsku - w komunikacie o brakującej zgodzie. */
        private val SCOPE_NAMES: Map<String, String> = mapOf(
            CalendarScopes.CALENDAR to "Kalendarz",
            CalendarScopes.CALENDAR_EVENTS to "Wydarzenia w kalendarzu",
            GmailScopes.GMAIL_READONLY to "Czytanie poczty",
            GmailScopes.GMAIL_SEND to "Wysyłanie poczty"
        )
    }
}
