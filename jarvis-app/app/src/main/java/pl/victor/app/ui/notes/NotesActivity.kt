package pl.victor.app.ui.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.victor.app.VictorApplication
import pl.victor.app.notes.Notes
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notatki - to, co użytkownik podyktował asystentowi.
 *
 * ## Dlaczego ekran jest tak prosty
 * Bo notatki powstają GŁOSEM, w biegu, i tu się je tylko przegląda albo kasuje.
 * Rozbudowany edytor obiecywałby, że to jest notatnik do pisania - a wtedy
 * pierwsze pytanie brzmi "gdzie foldery i tagi". Pole tekstowe jest jedno i
 * służy do dopisania czegoś, gdy akurat nie da się mówić.
 */
class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { NotesScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as VictorApplication).settings }
    var notes by remember { mutableStateOf(settings.getNotes()) }
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notatki") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Powiedz „zapisz, że mam kupić mleko” albo „dodaj kupić mleko”. " +
                        "Zapytaj „sprawdź w notatkach” albo „co mam do zrobienia”, " +
                        "a V.I.C.T.O.R. odpowie na ich podstawie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Dopisz notatkę") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            notes = settings.addNote(draft.trim())
                            draft = ""
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Text("Dodaj")
                    }
                }
            }

            if (notes.isEmpty()) {
                item {
                    Text(
                        "Nie masz jeszcze żadnych notatek.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            items(notes) { note ->
                NoteRow(
                    note = note,
                    onDelete = {
                        val updated = notes.filterNot {
                            it.text == note.text && it.createdAtMs == note.createdAtMs
                        }
                        settings.setNotes(updated)
                        notes = updated
                    }
                )
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun NoteRow(note: Notes.Note, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.text, fontWeight = FontWeight.Medium)
                if (note.createdAtMs > 0L) {
                    Text(
                        DATE_FORMAT.format(Date(note.createdAtMs)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Usuń notatkę")
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
