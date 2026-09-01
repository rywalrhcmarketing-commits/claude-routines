package pl.victor.app.actions

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import pl.victor.app.calendar.GoogleCalendarService
import pl.victor.app.google.GmailService

/**
 * Wykonuje akcje BEZPOŚREDNIO (bez otwierania zewnętrznej apki).
 *
 * Wymaga dangerous permissions:
 * - SEND_SMS do wysyłania SMS
 * - CALL_PHONE do dzwonienia
 * - READ_CONTACTS do rozwiązywania imion na numery
 *
 * Bezpieczeństwo:
 * - Wymaga wcześniejszego potwierdzenia dialogiem (ActionConfirmation.Required)
 * - Runtime permission check
 * - Try/catch z fallbackiem do trybu SAFE
 */
class DirectActionExecutor(private val context: Context) {

    private val tag = "DirectActionExecutor"
    private val contactResolver = ContactResolver(context)
    private val calendarService = GoogleCalendarService(context)
    private val gmailService = GmailService(context)

    /**
     * Sprawdza czy akcja może być wykonana bezpośrednio (wymaga permission).
     */
    fun canExecuteDirect(action: Action): ActionConfirmation {
        return when (action) {
            is Action.SendSms -> {
                if (!hasPermission(Manifest.permission.SEND_SMS)) {
                    return ActionConfirmation.NotRequired  // brak permission - użyj Intent
                }
                ActionConfirmation.Required(
                    title = "Wyślij SMS?",
                    message = "Czy na pewno chcesz wysłać SMS do ${action.to}?\n\n" +
                            "Treść: \"${action.body}\"",
                    confirmText = "📤 Wyślij"
                )
            }
            is Action.MakeCall -> {
                if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                    return ActionConfirmation.NotRequired
                }
                ActionConfirmation.Required(
                    title = "Zadzwonić?",
                    message = "Czy na pewno chcesz zadzwonić do ${action.to}?",
                    confirmText = "📞 Zadzwoń"
                )
            }
            is Action.CreateCalendarEvent -> {
                if (!calendarService.isSignedIn()) {
                    return ActionConfirmation.NotRequired  // brak konta - użyj Intentu (SAFE)
                }
                val whenText = java.text.SimpleDateFormat(
                    "EEEE d MMMM, HH:mm", java.util.Locale("pl", "PL")
                ).format(java.util.Date(action.startTimeMillis))
                ActionConfirmation.Required(
                    title = "Dodać do kalendarza?",
                    message = "„${action.title}” - $whenText",
                    confirmText = "📅 Dodaj"
                )
            }
            is Action.SendEmail -> {
                if (!gmailService.isSignedIn()) {
                    return ActionConfirmation.NotRequired  // brak konta - użyj Intentu (SAFE)
                }
                ActionConfirmation.Required(
                    title = "Wysłać email?",
                    message = "Do: ${action.to}\nTemat: ${action.subject}" +
                            if (action.body.isNotBlank()) "\n\n${action.body}" else "",
                    confirmText = "📧 Wyślij"
                )
            }
            else -> ActionConfirmation.NotRequired
        }
    }

    /**
     * Wykonuje akcję bezpośrednio. Wcześniej powinno być canExecuteDirect().
     */
    suspend fun executeDirect(action: Action): ActionResult {
        return try {
            when (action) {
                is Action.SendSms -> sendSmsDirect(action)
                is Action.MakeCall -> makeCallDirect(action)
                is Action.CreateCalendarEvent -> createCalendarEventDirect(action)
                is Action.SendEmail -> sendEmailDirect(action)
                else -> ActionResult.Failed("Ta akcja nie obsługuje trybu DIRECT")
            }
        } catch (e: SecurityException) {
            Log.w(tag, "Permission missing for $action", e)
            ActionResult.Failed("Brak uprawnień. Używam trybu bezpiecznego.")
        } catch (e: Exception) {
            Log.e(tag, "Direct execution failed", e)
            ActionResult.Failed(e.message ?: "Nieznany błąd")
        }
    }

    private suspend fun sendSmsDirect(action: Action.SendSms): ActionResult {
        // Rozwiąż nazwę na numer jeśli potrzeba
        val phoneNumber = resolvePhone(action.to)
        if (phoneNumber == null) {
            return ActionResult.Failed("Nie znaleziono numeru dla '${action.to}'")
        }

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return ActionResult.Failed("Brak SEND_SMS permission")
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Jeśli tekst długi (>160 znaków), podziel na części
            val parts = smsManager.divideMessage(action.body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, action.body, null, null)
            }
            Log.i(tag, "SMS sent to $phoneNumber (${action.body.length} chars)")
            ActionResult.Success("Wysłano SMS do ${action.to}")
        } catch (e: Exception) {
            Log.e(tag, "SmsManager failed", e)
            ActionResult.Failed("Wysyłanie SMS nie powiodło się: ${e.message}")
        }
    }

    private suspend fun makeCallDirect(action: Action.MakeCall): ActionResult {
        val phoneNumber = resolvePhone(action.to)
        if (phoneNumber == null) {
            return ActionResult.Failed("Nie znaleziono numeru dla '${action.to}'")
        }

        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return ActionResult.Failed("Brak CALL_PHONE permission")
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(tag, "Call started to $phoneNumber")
            ActionResult.Success("Dzwonię do ${action.to}")
        } catch (e: Exception) {
            Log.e(tag, "Call failed", e)
            ActionResult.Failed("Nie udało się zadzwonić: ${e.message}")
        }
    }

    private suspend fun createCalendarEventDirect(action: Action.CreateCalendarEvent): ActionResult {
        val created = calendarService.createEvent(
            title = action.title,
            startTimeMillis = action.startTimeMillis,
            durationMinutes = action.durationMinutes
        )
        return if (created != null) {
            Log.i(tag, "Calendar event created: ${created.id}")
            ActionResult.Success("Dodano do kalendarza: ${action.title}, ${created.startTimeFormatted()}")
        } else {
            ActionResult.Failed("Nie udało się dodać wydarzenia do kalendarza")
        }
    }

    private suspend fun sendEmailDirect(action: Action.SendEmail): ActionResult {
        val sent = gmailService.sendEmail(
            to = action.to,
            subject = action.subject,
            body = action.body
        )
        return if (sent) {
            ActionResult.Success("Wysłano email do ${action.to}")
        } else {
            ActionResult.Failed("Nie udało się wysłać emaila")
        }
    }

    private suspend fun resolvePhone(name: String): String? {
        // Jeśli wygląda jak numer telefonu - zwróć bezpośrednio
        if (name.filter { it.isDigit() }.length >= 5) {
            return name
        }
        // W przeciwnym razie szukaj w kontaktach
        return contactResolver.findPhoneNumber(name)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lista uprawnień potrzebnych do trybu DIRECT.
     */
    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
    }
}
