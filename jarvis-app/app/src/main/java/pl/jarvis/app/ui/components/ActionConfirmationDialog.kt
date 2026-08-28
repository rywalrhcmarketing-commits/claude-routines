package pl.jarvis.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import pl.jarvis.app.PendingActionConfirmation

/**
 * Dialog potwierdzenia akcji (SMS, call) w trybie DIRECT.
 * Wyświetlany na górze wszystkiego - user musi zdecydować.
 */
@Composable
fun ActionConfirmationDialog(
    pending: PendingActionConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                pending.title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                pending.message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(pending.confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(pending.cancelText)
            }
        }
    )
}
