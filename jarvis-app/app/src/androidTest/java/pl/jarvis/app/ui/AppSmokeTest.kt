package pl.jarvis.app.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.jarvis.app.JarvisApplication
import pl.jarvis.app.ui.diagnostics.DiagnosticsActivity
import pl.jarvis.app.ui.history.HistoryActivity
import pl.jarvis.app.ui.pairing.PairingActivity
import pl.jarvis.app.ui.settings.SettingsActivity

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

    @Before
    fun setUp() {
        // Bez tego MainActivity przekierowuje na onboarding i od razu się kończy.
        val app = ApplicationProvider.getApplicationContext<JarvisApplication>()
        app.settings.setOnboardingCompleted(true)
    }

    @Test
    fun aplikacjaMaZainicjalizowaneZaleznosci() {
        val app = ApplicationProvider.getApplicationContext<JarvisApplication>()
        assertNotNull("SettingsRepository nie powstał", app.settings)
        assertNotNull("JarvisManager nie powstał", app.heyCyanManager)
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
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun ustawieniaPrzezywajaZapisIOdczyt() {
        // EncryptedSharedPreferences potrafi się wywrócić na urządzeniu bez
        // sprawnego keystore - lepiej dowiedzieć się o tym tutaj.
        val app = ApplicationProvider.getApplicationContext<JarvisApplication>()
        app.settings.setResponseLanguage("en")
        assertEquals("en", app.settings.getResponseLanguage())
        app.settings.setResponseLanguage("pl")
        assertEquals("pl", app.settings.getResponseLanguage())
    }

    @Test
    fun kluczApiSaSzyfrowaneWSpolnychPreferencjach() {
        val app = ApplicationProvider.getApplicationContext<JarvisApplication>()
        val secret = "test-klucz-do-skasowania"
        app.settings.setApiKey("gemini", secret)
        assertEquals(secret, app.settings.getApiKey("gemini"))

        // Plik preferencji nie może zawierać klucza otwartym tekstem.
        val prefsFile = java.io.File(
            app.applicationInfo.dataDir,
            "shared_prefs/jarvis_secure_prefs.xml"
        )
        if (prefsFile.exists()) {
            assertTrue(
                "klucz API znaleziony otwartym tekstem w preferencjach",
                !prefsFile.readText().contains(secret)
            )
        }
        app.settings.setApiKey("gemini", "")
    }
}
