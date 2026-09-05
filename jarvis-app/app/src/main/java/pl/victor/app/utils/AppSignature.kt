package pl.victor.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Nazwa pakietu i odcisk SHA-1 podpisu tej aplikacji.
 *
 * ## Po co
 * Logowanie Google wiąże klienta OAuth z PARĄ: nazwa pakietu + odcisk SHA-1
 * certyfikatu, którym podpisano APK. Gdy para się nie zgadza, Usługi Google
 * odmawiają z kodem DEVELOPER_ERROR - i to jest dokładnie ten komunikat
 * "klient OAuth nie jest skonfigurowany dla tej wersji aplikacji".
 *
 * Żeby to naprawić, trzeba wpisać obie wartości w Google Cloud Console. Bez tej
 * klasy użytkownik musiałby je wyciągać z APK narzędziem `keytool` na komputerze
 * - czyli praktycznie nie miałby jak. Tu są do odczytania wprost z telefonu, z
 * ekranu Diagnostyki.
 */
object AppSignature {

    private const val TAG = "AppSignature"

    /** Nazwa pakietu, z sufiksem `.debug` włącznie. */
    fun packageName(context: Context): String = context.packageName

    /**
     * Odcisk SHA-1 certyfikatu podpisu, w formacie `AA:BB:CC:...` - tak jak
     * oczekuje go Google Cloud Console.
     *
     * @return odcisk albo `null`, gdy systemu nie da się o niego zapytać
     */
    fun sha1(context: Context): String? = try {
        certificateBytes(context)?.let { bytes ->
            MessageDigest.getInstance("SHA-1")
                .digest(bytes)
                .joinToString(":") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Nie udało się odczytać odcisku podpisu", e)
        null
    }

    @Suppress("DEPRECATION")
    private fun certificateBytes(context: Context): ByteArray? {
        val pm = context.packageManager
        val name = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            // Klucz mógł zostać wymieniony (rotacja) - wtedy liczy się bieżący.
            val signatures = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            signatures?.firstOrNull()?.toByteArray()
        } else {
            val info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        }
    }
}
