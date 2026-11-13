package bb.produce.invoicing.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bb.produce.invoicing.domain.common.BarbadosTime
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.payment.PaymentMethod
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.domain.printing.PrintStatus
import bb.produce.invoicing.ui.theme.PiColors
import bb.produce.invoicing.ui.theme.PiMono
import java.time.Instant

enum class Tone(val fg: Color, val bg: Color) {
    GOOD(PiColors.ForestDeep, PiColors.ForestTint),
    WARN(PiColors.Amber, PiColors.AmberTint),
    BAD(PiColors.Red, Color(0xFFFBECE8)),
    NEUTRAL(PiColors.InkMuted, Color(0xFFEEEAE1)),
}

data class Badge(val label: String, val tone: Tone)

/*
 * Status wording. Each dimension is shown separately and never collapsed into one green tick (docs/06).
 * "Synced" appears only after a real server acknowledgement; "Printed" only after confirmation.
 */
fun syncBadge(s: SyncState) = when (s) {
    SyncState.PENDING -> Badge("Not synced", Tone.WARN)
    SyncState.SYNCING -> Badge("Syncing…", Tone.NEUTRAL)
    SyncState.SYNCED -> Badge("Synced to office", Tone.GOOD)
    SyncState.CONFLICT -> Badge("Needs review", Tone.BAD)
    SyncState.FAILED_RETRYABLE -> Badge("Sync failed · will retry", Tone.WARN)
}

fun printBadge(s: PrintStatus) = when (s) {
    PrintStatus.NOT_PRINTED -> Badge("Not printed", Tone.NEUTRAL)
    PrintStatus.QUEUED, PrintStatus.ATTEMPTING -> Badge("Printing…", Tone.NEUTRAL)
    PrintStatus.TRANSPORT_SENT -> Badge("Sent to printer · not confirmed", Tone.WARN)
    PrintStatus.CONFIRMED -> Badge("Printed (confirmed)", Tone.GOOD)
    PrintStatus.UNCERTAIN -> Badge("Check printer", Tone.WARN)
    PrintStatus.FAILED -> Badge("Print failed", Tone.BAD)
}

fun paymentBadge(s: PaymentStatus, method: PaymentMethod? = null) = when (s) {
    PaymentStatus.UNPAID -> Badge("Unpaid", Tone.WARN)
    PaymentStatus.PAID -> Badge("Paid" + (method?.let { " · ${methodLabel(it)}" } ?: ""), Tone.GOOD)
}

fun issueBadge(voided: Boolean) = if (voided) Badge("Void", Tone.BAD) else Badge("Issued", Tone.GOOD)

fun methodLabel(m: PaymentMethod) = when (m) {
    PaymentMethod.CASH -> "Cash"
    PaymentMethod.BANK_TRANSFER -> "Bank transfer"
    PaymentMethod.CHEQUE -> "Cheque"
    PaymentMethod.CARD_EXTERNAL -> "Card (external terminal)"
    PaymentMethod.OTHER -> "Other"
    PaymentMethod.NONE -> "Not selected"
}

fun timeLabel(i: Instant) = BarbadosTime.display(i)

@Composable
fun StatusBadge(badge: Badge, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(badge.tone.bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: ${badge.label}" },
    ) {
        Text(badge.label, color = badge.tone.fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BadgeRow(vararg badges: Badge) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { StatusBadge(it) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, fontFamily = PiMono,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Notice(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(tone.bg, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(text, color = tone.fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Dialog for small forms. Deliberately NOT Material3 AlertDialog: an OutlinedTextField inside AlertDialog
 * never let Compose become idle in the Robolectric UI tests (Task 03 finding; on-device check pending),
 * so forms with text input use a plain Dialog + Surface instead.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                }
            }
        }
    }
}

/** Confirmation that optionally requires a reason (voids, reversals, discards). */
@Composable
fun ReasonDialog(
    title: String,
    body: String,
    confirmLabel: String,
    requireReason: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    FormDialog(title, onDismiss, confirmLabel, confirmEnabled = !requireReason || reason.isNotBlank(), onConfirm = { onConfirm(reason) }) {
        Text(body)
        if (requireReason) OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, modifier = Modifier.fillMaxWidth().testTag("reason-input"))
    }
}
