package pl.jarvis.app.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import pl.jarvis.app.R

/**
 * Wykonuje akcje przez Android Intents.
 *
 * UWAGA: Apka NIGDY nie robi nic bezpośrednio (nie wysyła SMS, nie dzwoni).
 * Zamiast tego otwiera odpowiednią apkę z przygotowanymi parametrami.
 * User musi potwierdzić ostatni krok (wciśnięcie "wyślij" w SMS, "zadzwoń" w dialer).
 *
 * Dlaczego to bezpieczniejsze:
 * - Nie potrzeba dangerous permissions (CALL_PHONE, SEND_SMS, READ_CONTACTS)
 * - User widzi co się dzieje
 * - Może anulować w ostatniej chwili
 * - Kontakty/numery są w systemie, nie w naszej apce
 */
class ActionExecutor(private val context: Context) {

    private val tag = "ActionExecutor"

    /**
     * Wykonuje akcję. Zwraca rezultat.
     */
    fun execute(action: Action): ActionResult {
        Log.i(tag, "Executing: ${action.type} - ${action.description}")
        return try {
            when (action) {
                is Action.SendSms -> sendSms(action)
                is Action.MakeCall -> makeCall(action)
                is Action.SendEmail -> sendEmail(action)
                is Action.PlayMusic -> playMusic(action)
                is Action.TogglePlayPause -> togglePlayPause()
                is Action.SkipTrack -> skipTrack(action)
                is Action.Navigate -> navigate(action)
                is Action.SetAlarm -> setAlarm(action)
                is Action.SetTimer -> setTimer(action)
                is Action.WebSearch -> webSearch(action)
                is Action.OpenUrl -> openUrl(action)
                is Action.OpenApp -> openApp(action)
                is Action.Translate -> translate(action)
                is Action.ShowOnMap -> showOnMap(action)
                is Action.ToggleWifi -> toggleWifi(action)
                is Action.ToggleBluetooth -> toggleBluetooth(action)
                is Action.ToggleFlashlight -> toggleFlashlight(action)
                // Tryby dostępności obsługuje AIOrchestrator przez AccessibilityService -
                // odfiltrowuje je zanim trafią tutaj. Gałąź istnieje, bo Kotlin
                // wymaga wyczerpania when po typie Action.
                is Action.ReadText,
                is Action.DescribeScene,
                is Action.StartNavigation,
                is Action.StopAccessibility ->
                    ActionResult.Failed("Tryb dostępności obsługiwany poza ActionExecutor")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to execute ${action.type}", e)
            ActionResult.Failed(e.message ?: "Nieznany błąd")
        }
    }

    // === Komunikacja ===

    private fun sendSms(action: Action.SendSms): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${action.to}")
            putExtra("sms_body", action.body)
        }
        return launchIntent(intent, "Klient SMS nie jest zainstalowany")
    }

    private fun makeCall(action: Action.MakeCall): ActionResult {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${action.to}")
        }
        // ACTION_DIAL nie wymaga CALL_PHONE - otwiera dialer z numerem, user wciska "zadzwoń"
        return launchIntent(intent, "Dialer nie jest dostępny")
    }

    private fun sendEmail(action: Action.SendEmail): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${action.to}")
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
        }
        return launchIntent(intent, "Brak klienta email")
    }

    // === Muzyka ===

    private fun playMusic(action: Action.PlayMusic): ActionResult {
        // Próba 1: Spotify
        val spotifyUri = Uri.parse("spotify:search:${Uri.encode(action.query)}")
        val spotifyIntent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
            setPackage("com.spotify.music")
        }
        if (spotifyIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(spotifyIntent, "Nie udało się otworzyć Spotify")
        }

        // Próba 2: YouTube Music
        val ytUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(action.query)}")
        val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
            setPackage("com.google.android.apps.youtube.music")
        }
        if (ytIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(ytIntent, "Nie udało się otworzyć YT Music")
        }

        // Próba 3: System media search
        val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.CONTENT_TYPE)
            putExtra("query", action.query)
        }
        if (mediaIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(mediaIntent, "Nie udało się wyszukać muzyki")
        }

        // Fallback: YouTube (regularny)
        val youtubeIntent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(action.query)}"))
        return launchIntent(youtubeIntent, "Brak aplikacji muzycznej")
    }

    private fun togglePlayPause(): ActionResult {
        // MediaSession przez intent
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            ))
        }
        return launchIntent(intent, "Brak aktywnego odtwarzacza muzyki")
    }

    private fun skipTrack(action: Action.SkipTrack): ActionResult {
        val keyCode = if (action.direction == SkipDirection.NEXT)
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS

        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, keyCode
            ))
        }
        return launchIntent(intent, "Brak aktywnego odtwarzacza")
    }

    // === Nawigacja ===

    private fun navigate(action: Action.Navigate): ActionResult {
        // Google Maps
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(action.destination)}")
        val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (mapsIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(mapsIntent, "Brak aplikacji map")
        }
        return ActionResult.Failed("Zainstaluj Google Maps do nawigacji")
    }

    private fun showOnMap(action: Action.ShowOnMap): ActionResult {
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(action.query)}")
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        return launchIntent(intent, "Brak aplikacji map")
    }

    // === Narzędzia ===

    private fun setAlarm(action: Action.SetAlarm): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            if (action.label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launchIntent(intent, "Brak aplikacji zegara")
    }

    private fun setTimer(action: Action.SetTimer): ActionResult {
        val totalSeconds = action.minutes * 60 + action.seconds
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launchIntent(intent, "Brak aplikacji zegara")
    }

    private fun webSearch(action: Action.WebSearch): ActionResult {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(action.query)}"))
        return launchIntent(intent, "Brak przeglądarki")
    }

    private fun openUrl(action: Action.OpenUrl): ActionResult {
        val uri = if (action.url.startsWith("http")) Uri.parse(action.url)
                  else Uri.parse("https://${action.url}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return launchIntent(intent, "Nie udało się otworzyć URL")
    }

    private fun openApp(action: Action.OpenApp): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ActionResult.Success("Otwieram ${action.appName.ifBlank { action.packageName }}")
        }
        return ActionResult.Failed("Aplikacja ${action.appName} nie jest zainstalowana")
    }

    private fun translate(action: Action.Translate): ActionResult {
        // Android 12+ ma systemowy intent do tłumaczenia
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                putExtra(Intent.EXTRA_PROCESS_TEXT, action.text)
                type = "text/plain"
            }
            // Sprawdź czy jest jakaś apka do tłumaczenia
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            val translateActivity = activities.find { info ->
                info.activityInfo.packageName.contains("translate", ignoreCase = true)
            }
            if (translateActivity != null) {
                intent.setPackage(translateActivity.activityInfo.packageName)
                intent.setClassName(
                    translateActivity.activityInfo.packageName,
                    translateActivity.activityInfo.name
                )
                context.startActivity(intent)
                return ActionResult.Success("Tłumaczę")
            }
        }
        return ActionResult.Failed("Zainstaluj Google Translate lub inną apkę do tłumaczenia")
    }

    // === System ===

    private fun toggleWifi(action: Action.ToggleWifi): ActionResult {
        // Android 10+ nie pozwala programowo włączać WiFi
        // Otwieramy ustawienia
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success("Otwieram ustawienia WiFi")
    }

    private fun toggleBluetooth(action: Action.ToggleBluetooth): ActionResult {
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success("Otwieram ustawienia Bluetooth")
    }

    private fun toggleFlashlight(action: Action.ToggleFlashlight): ActionResult {
        // Próba: CameraManager (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            try {
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, action.enabled)
                    return ActionResult.Success(if (action.enabled) "Latarka włączona" else "Latarka wyłączona")
                }
            } catch (e: Exception) {
                Log.w(tag, "Flashlight toggle failed", e)
            }
        }
        return ActionResult.Failed("Nie udało się sterować latarką")
    }

    // === Helpers ===

    private fun launchIntent(intent: Intent, errorIfNotFound: String): ActionResult {
        if (intent.resolveActivity(context.packageManager) == null) {
            return ActionResult.Failed(errorIfNotFound)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success("Otwarto")
    }

    /**
     * Lista zainstalowanych popularnych apek (do UI "Otwórz apkę").
     */
    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val popularPackages = listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.maps" to "Google Maps",
            "com.google.android.apps.photos" to "Google Photos",
            "com.google.android.gm" to "Gmail",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "Twitter",
            "org.telegram.messenger" to "Telegram",
            "com.slack" to "Slack",
            "com.netflix.mediaclient" to "Netflix",
            "com.amazon.mShop.android.shopping" to "Amazon",
            "com.uber" to "Uber",
            "pl.jarvis.app" to "Jarvis (ta apka)"
        )

        return popularPackages.mapNotNull { (pkg, name) ->
            try {
                pm.getPackageInfo(pkg, 0)
                AppInfo(pkg, name, true)
            } catch (e: PackageManager.NameNotFoundException) {
                AppInfo(pkg, name, false)
            }
        }
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val installed: Boolean
)
