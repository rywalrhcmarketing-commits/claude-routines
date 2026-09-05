package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Własne komendy uruchamiają akcje bez udziału modelu, więc pomyłka jest tu
 * kosztowna: przypadkowe dopasowanie znaczy wykonaną akcję, o którą nikt nie
 * prosił. Te testy pilnują przede wszystkim tego, CZEGO dopasować nie wolno.
 */
class CustomCommandsTest {

    private val flashlight = CustomCommands.CustomCommand(
        phrase = "dobranoc",
        type = ActionType.TOGGLE_FLASHLIGHT,
        argument = "wyłącz"
    )

    @Test
    fun `fraza dopasowuje sie mimo wielkich liter i kropki`() {
        assertNotNull(CustomCommands.match("Dobranoc.", listOf(flashlight)))
    }

    @Test
    fun `podwojne spacje i interpunkcja nie psuja dopasowania`() {
        val cmd = CustomCommands.CustomCommand("daj  światło", ActionType.TOGGLE_FLASHLIGHT)
        assertNotNull(CustomCommands.match("Daj, światło!", listOf(cmd)))
    }

    @Test
    fun `ogonki maja znaczenie`() {
        // Normalizacja świadomie NIE usuwa diakrytyków: "świat" i "swiat" to
        // dwa różne słowa. Gdyby je zrównać, krótkie frazy zaczęłyby zderzać
        // się ze sobą, a to przy własnych komendach kończy się wykonaniem
        // czegoś, o co nikt nie prosił.
        val cmd = CustomCommands.CustomCommand("światło", ActionType.TOGGLE_FLASHLIGHT)
        assertNull(CustomCommands.match("swiatlo", listOf(cmd)))
    }

    @Test
    fun `fragment zdania NIE uruchamia komendy`() {
        // To jest najważniejszy test w tym pliku: przy dopasowaniu po fragmencie
        // "nie mów dobranoc" gasiłoby latarkę.
        assertNull(CustomCommands.match("nie mów dobranoc", listOf(flashlight)))
        assertNull(CustomCommands.match("czy mam powiedzieć dobranoc?", listOf(flashlight)))
    }

    @Test
    fun `pusta wypowiedz nie dopasowuje sie do niczego`() {
        assertNull(CustomCommands.match("", listOf(flashlight)))
        assertNull(CustomCommands.match("   ", listOf(flashlight)))
    }

    @Test
    fun `przy kilku pasujacych wygrywa pierwsza`() {
        val a = CustomCommands.CustomCommand("test", ActionType.TAKE_PHOTO)
        val b = CustomCommands.CustomCommand("test", ActionType.TOGGLE_PLAY)
        assertEquals(a, CustomCommands.match("test", listOf(a, b)))
    }

    @Test
    fun `jednoliterowa fraza jest odrzucana`() {
        // Przy tak krótkiej frazie każde przesłyszenie uruchamiałoby akcję.
        assertFalse(CustomCommands.isValidPhrase("a"))
        assertFalse(CustomCommands.isValidPhrase(" "))
        assertFalse(CustomCommands.isValidPhrase("!"))
        assertTrue(CustomCommands.isValidPhrase("ok"))
    }

    @Test
    fun `akcje bezparametrowe budują się bez argumentu`() {
        val cmd = CustomCommands.CustomCommand("foto", ActionType.TAKE_PHOTO)
        assertEquals(Action.TakePhoto, CustomCommands.toAction(cmd))
    }

    @Test
    fun `latarka rozroznia wlaczenie od wylaczenia`() {
        val on = CustomCommands.CustomCommand("swiec", ActionType.TOGGLE_FLASHLIGHT, "włącz")
        val off = CustomCommands.CustomCommand("ciemno", ActionType.TOGGLE_FLASHLIGHT, "wyłącz")
        assertEquals(Action.ToggleFlashlight(true), CustomCommands.toAction(on))
        assertEquals(Action.ToggleFlashlight(false), CustomCommands.toAction(off))
    }

    @Test
    fun `akcja wymagajaca parametru bez parametru nie powstaje`() {
        // Lepiej nie zrobić nic, niż zadzwonić pod pusty numer.
        val cmd = CustomCommands.CustomCommand("dzwon", ActionType.MAKE_CALL, argument = "")
        assertNull(CustomCommands.toAction(cmd))
    }

    @Test
    fun `akcje wielopolowe zostaja modelowi`() {
        // SMS potrzebuje odbiorcy I treści - jedno pole tekstowe im nie starczy.
        listOf(
            ActionType.SEND_SMS,
            ActionType.SEND_EMAIL,
            ActionType.SET_ALARM,
            ActionType.CREATE_CALENDAR_EVENT
        ).forEach { type ->
            assertNull(
                "$type nie powinien być do przypisania",
                CustomCommands.toAction(CustomCommands.CustomCommand("x", type, "cokolwiek"))
            )
            assertFalse(type in CustomCommands.ASSIGNABLE_TYPES)
        }
    }

    @Test
    fun `lista typow do przypisania zawiera te bezparametrowe i jednoparametrowe`() {
        assertTrue(ActionType.TAKE_PHOTO in CustomCommands.ASSIGNABLE_TYPES)
        assertTrue(ActionType.MAKE_CALL in CustomCommands.ASSIGNABLE_TYPES)
        assertTrue(ActionType.OPEN_APP in CustomCommands.ASSIGNABLE_TYPES)
    }

    @Test
    fun `needsArgument odroznia akcje z parametrem od bezparametrowych`() {
        assertTrue(CustomCommands.needsArgument(ActionType.MAKE_CALL))
        assertFalse(CustomCommands.needsArgument(ActionType.TAKE_PHOTO))
    }
}
