package pl.victor.app.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
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
}
