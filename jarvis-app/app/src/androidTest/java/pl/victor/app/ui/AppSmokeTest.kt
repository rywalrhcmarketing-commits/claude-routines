package pl.victor.app.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.victor.app.VictorApplication
import pl.victor.app.ui.diagnostics.DiagnosticsActivity
import pl.victor.app.ui.history.HistoryActivity
import pl.victor.app.ui.pairing.PairingActivity
import pl.victor.app.ui.settings.SettingsActivity

/**
 * Test dymny: aplikacja wstaje i każdy ekran daje się otworzyć bez wywrotki.
 *
 * To najtańszy sposób na złapanie błędów, których kompilator nie widzi:
 * brakującej aktywności w manifeście, wyjątku w `onCreate`, wysypki przy
 * budowaniu drzewa Compose albo braku zależności potrzebnej dopiero
 * w czasie działania.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    private val app: VictorApplication
        get() = ApplicationProvider.getApplicationContext()

    private var onboardingWas = false
    private var languageWas = "pl"

    @Before
    fun setUp() {
        // Test biegnie na tym samym pakiecie co aplikacja użytkownika, więc
        // zapamiętujemy stan i oddajemy go po sobie.
        onboardingWas = app.settings.isOnboardingCompleted()
        languageWas = app.settings.getResponseLanguage()

        // Bez tego MainActivity przekierowuje na onboarding i od razu się kończy.
        app.settings.setOnboardingCompleted(true)
    }

    @After
    fun tearDown() {
        app.settings.setOnboardingCompleted(onboardingWas)
        app.settings.setResponseLanguage(languageWas)
    }

    @Test
    fun aplikacjaMaZainicjalizowaneZaleznosci() {
        assertNotNull("SettingsRepository nie powstał", app.settings)
        assertNotNull("VictorManager nie powstał", app.glassesManager)
        assertNotNull("baza danych nie powstała", app.database)
        assertNotNull("AIOrchestrator nie powstał", app.orchestrator)
    }

    @Test
    fun ekranGlownyWstajeBezWywrotki() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun ekranUstawienWstajeBezWywrotki() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun ekranDiagnostykiWstajeBezWywrotki() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun ekranHistoriiWstajeBezWywrotki() {
        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun ekranParowaniaWstajeBezWywrotki() {
        ActivityScenario.launch(PairingActivity::class.java).use { scenario ->
            // Ten ekran prosi w onCreate o uprawnienia Bluetooth i Wi-Fi, więc
            // systemowy dialog przykrywa aktywność i zostaje ona w STARTED.
            // Dla testu dymnego liczy się to, że w ogóle wstała.
            assertTrue(
                "PairingActivity nie doszła nawet do STARTED, stan: ${scenario.state}",
                scenario.state.isAtLeast(Lifecycle.State.STARTED)
            )
        }
    }

    @Test
    fun ustawieniaPrzezywajaZapisIOdczyt() {
        // EncryptedSharedPreferences potrafi się wywrócić na urządzeniu bez
        // sprawnego keystore - lepiej dowiedzieć się o tym tutaj.
        app.settings.setResponseLanguage("en")
        assertEquals("en", app.settings.getResponseLanguage())
        app.settings.setResponseLanguage("pl")
        assertEquals("pl", app.settings.getResponseLanguage())
    }

    @Test
    fun kluczApiSaSzyfrowaneWSpolnychPreferencjach() {
        // Zmyślony provider - test nie może nadpisać prawdziwego klucza,
        // gdyby ktoś odpalił go na własnym telefonie.
        val provider = "__test_provider__"
        val secret = "sekret-testowy-4f2a9c"

        app.settings.setApiKey(provider, secret)
        assertEquals(secret, app.settings.getApiKey(provider))

        // Plik preferencji nie może zawierać klucza otwartym tekstem.
        val prefsFile = java.io.File(
            app.applicationInfo.dataDir,
            "shared_prefs/victor_secure_prefs.xml"
        )
        assertTrue(
            "nie znalazłem pliku preferencji - test nic by nie sprawdził",
            prefsFile.exists()
        )
        assertTrue(
            "klucz API leży otwartym tekstem w ${prefsFile.name}",
            !prefsFile.readText().contains(secret)
        )
    }
}
