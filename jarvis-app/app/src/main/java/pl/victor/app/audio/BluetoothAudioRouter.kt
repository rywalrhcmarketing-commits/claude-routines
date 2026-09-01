package pl.victor.app.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kieruje nasłuch głosu (wake word, rozpoznawanie mowy) przez sparowane
 * urządzenie audio Bluetooth zamiast mikrofonu telefonu - z automatycznym
 * powrotem na mikrofon telefonu, gdy żadne urządzenie nie jest podłączone
 * albo połączenie się nie uda.
 *
 * ## To NIE jest protokół BLE okularów
 * Zdjęcia/AI/przycisk fizyczny idą przez BLE (`VictorManager`) - osobny,
 * niskoprzepustowy kanał, nie nadający się do dźwięku. To jest standardowy,
 * inny mechanizm Androida (Bluetooth Classic SCO/HFP), który działa
 * identycznie dla każdego urządzenia audio Bluetooth (słuchawek, zestawu
 * głośnomówiącego w samochodzie, i - jeśli okulary go obsługują - dla nich
 * też). Apka nie musi znać żadnego szczegółu protokołu okularów, żeby z
 * tego skorzystać - i dlatego to bezpieczne do napisania bez sprzętu w ręku:
 * jeśli okulary nie obsługują SCO, [startScoAndAwait] po prostu zwróci
 * `false` i wszystko zostaje jak jest dziś (mikrofon telefonu).
 */
class BluetoothAudioRouter(private val context: Context) {

    private val tag = "BluetoothAudioRouter"
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var scoReceiver: BroadcastReceiver? = null

    /** Czy telefon widzi podłączone (nie tylko sparowane) urządzenie audio Bluetooth. */
    fun hasConnectedBluetoothAudioDevice(): Boolean {
        val am = audioManager ?: return false
        if (!hasBluetoothPermission()) return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się sprawdzić urządzeń audio", e)
            false
        }
    }

    /**
     * Uruchamia łącze SCO i czeka aż faktycznie się połączy (albo minie timeout).
     * Bezpieczne do wołania zawsze - gdy nie ma urządzenia Bluetooth, po prostu
     * od razu zwraca `false` bez efektów ubocznych.
     *
     * @return `true` gdy SCO faktycznie działa - wtedy warto użyć
     *   [android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION] do nagrywania
     */
    suspend fun startScoAndAwait(timeoutMs: Long = 3_000L): Boolean {
        val am = audioManager ?: return false
        if (!hasConnectedBluetoothAudioDevice()) return false

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> deferred.complete(true)
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> deferred.complete(false)
                }
            }
        }
        scoReceiver = receiver

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            @Suppress("DEPRECATION")
            am.startBluetoothSco()

            val connected = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            if (connected) {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                Log.i(tag, "SCO połączone - nasłuch przez urządzenie Bluetooth")
            } else {
                Log.d(tag, "SCO nie połączyło się w ${timeoutMs}ms - zostaję na mikrofonie telefonu")
                stopSco()
            }
            connected
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się uruchomić SCO", e)
            stopSco()
            false
        }
    }

    /** Zatrzymuje łącze SCO. Bezpieczne do wołania nawet gdy nic nie było uruchomione. */
    fun stopSco() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
                // Już wyrejestrowany albo nigdy nie zarejestrowany - nic się nie stało.
            }
        }
        scoReceiver = null
        val am = audioManager ?: return
        try {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się zatrzymać SCO", e)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
