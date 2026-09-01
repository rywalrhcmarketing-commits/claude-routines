package pl.victor.app.actions

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rozwiązuje nazwy kontaktów na numery telefonów i emaile.
 *
 * Wymaga READ_CONTACTS permission. W trybie SAFE nie jest potrzebny
 * (bo system sam rozwiązuje przez dialer/SMS).
 *
 * Algorytm:
 * 1. Normalizuj nazwę (lowercase, bez polskich znaków opcjonalnie)
 * 2. Szukaj w ContactsContract.Phone
 * 3. Dopasuj: substring / startsWith / fuzzy
 */
class ContactResolver(private val context: Context) {

    private val tag = "ContactResolver"

    /**
     * Szuka kontaktu o podanej nazwie, zwraca numer telefonu lub null.
     */
    suspend fun findPhoneNumber(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val normalized = normalize(name)

            // Najpierw szukaj po displayName
            val byName = searchByName(normalized)
            if (byName != null) return@withContext byName

            // Fallback: sprawdź czy to może numer telefonu już
            if (isPhoneNumber(name)) return@withContext name

            null
        } catch (e: SecurityException) {
            Log.w(tag, "READ_CONTACTS permission not granted")
            null
        } catch (e: Exception) {
            Log.e(tag, "Contact lookup failed", e)
            null
        }
    }

    /**
     * Szuka emaila po nazwie kontaktu.
     */
    suspend fun findEmail(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val normalized = normalize(name)
            val contactId = findContactId(normalized) ?: return@withContext null

            val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?"
            val args = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            )

            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(tag, "READ_CONTACTS permission not granted")
            null
        } catch (e: Exception) {
            Log.e(tag, "Email lookup failed", e)
            null
        }
    }

    /**
     * Szuka numeru po nazwie, zwraca parę (contactId, phone).
     */
    private fun searchByName(normalizedName: String): String? {
        val contactId = findContactId(normalizedName) ?: return null

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        )

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    /**
     * Szuka contactId po displayName (fuzzy match).
     */
    private fun findContactId(name: String): Long? {
        if (name.isBlank()) return null

        val normalizedName = normalize(name)

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        // Szukaj contacts które zawierają nazwę
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val args = arrayOf("%$name%")

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val displayName = cursor.getString(1) ?: ""
                val normalizedDisplay = normalize(displayName)

                // Fuzzy: sprawdź czy nazwa pasuje
                if (matches(normalizedName, normalizedDisplay)) {
                    return id
                }
            }
        }

        return null
    }

    /**
     * Dopasowanie: substring lub startsWith.
     */
    private fun matches(query: String, displayName: String): Boolean {
        if (query.isBlank() || displayName.isBlank()) return false
        return displayName.contains(query, ignoreCase = true) ||
                query.contains(displayName, ignoreCase = true) ||
                firstNameMatches(query, displayName)
    }

    private fun firstNameMatches(query: String, displayName: String): Boolean {
        val firstName = displayName.split(" ").firstOrNull() ?: return false
        return firstName.equals(query, ignoreCase = true) ||
                firstName.startsWith(query, ignoreCase = true)
    }

    private fun normalize(s: String): String {
        return s.trim().lowercase()
            .replace("ą", "a").replace("ć", "c").replace("ę", "e")
            .replace("ł", "l").replace("ń", "n").replace("ó", "o")
            .replace("ś", "s").replace("ź", "z").replace("ż", "z")
    }

    /**
     * Czy `s` wygląda już jak numer telefonu (a nie nazwa kontaktu).
     * Publiczne - wywołujący (np. orkiestrator) musi to sprawdzić, zanim
     * w ogóle spróbuje szukać w kontaktach.
     */
    fun isPhoneNumber(s: String): Boolean {
        // Prosty check: więcej niż 5 cyfr
        val digits = s.filter { it.isDigit() }
        return digits.length >= 5
    }
}
