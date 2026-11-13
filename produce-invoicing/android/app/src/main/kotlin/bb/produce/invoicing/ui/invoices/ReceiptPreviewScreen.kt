package bb.produce.invoicing.ui.invoices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.printing.Receipt58
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.EmptyState
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.Tone
import bb.produce.invoicing.ui.theme.PiColors
import bb.produce.invoicing.ui.theme.PiMono

/**
 * 58 mm receipt preview from the stored invoice snapshot. Printing itself is disabled until a printer
 * profile exists (Tasks 06–07); nothing here claims that anything was printed.
 */
@Composable
fun ReceiptPreviewScreen(session: Session, id: InvoiceId) {
    val c = LocalAppContainer.current
    val invoice by produceState<IssuedInvoice?>(null, id) { value = c.readModel.invoiceFor(session.actor, c.repository, id) }
    var copy by remember { mutableStateOf(false) }
    val inv = invoice
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel("58 mm receipt preview · ${Receipt58.DEFAULT_COLUMNS} columns")
        if (inv == null) { EmptyState("Invoice not available", ""); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !copy, onClick = { copy = false }, label = { Text("Original") })
            FilterChip(selected = copy, onClick = { copy = true }, label = { Text("Reprint (COPY)") })
        }
        val lines = Receipt58.render(inv, copy = copy)
        Column(
            Modifier.background(Color.White).border(1.dp, PiColors.Border).padding(12.dp).horizontalScroll(rememberScrollState())
                .testTag("receipt-preview"),
        ) {
            lines.forEach { Text(it, fontFamily = PiMono, fontSize = 12.sp, color = PiColors.Ink, softWrap = false) }
        }
        Notice("Layout preview only. Business details, tax wording and legal fields still need client/accountant approval.", Tone.NEUTRAL)
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Print") }
        Text("No printer is set up on this device. Printing arrives with the printer setup (Tasks 06–07).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
