package bb.produce.invoicing.ui.invoices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.data.InvoiceListItem
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.invoice.InvoiceStatus
import bb.produce.invoicing.domain.payment.PaymentMethod
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.BadgeRow
import bb.produce.invoicing.ui.common.EmptyState
import bb.produce.invoicing.ui.common.FormDialog
import bb.produce.invoicing.ui.common.KeyValue
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.PiCard
import bb.produce.invoicing.ui.common.ReasonDialog
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.Tone
import bb.produce.invoicing.ui.common.issueBadge
import bb.produce.invoicing.ui.common.methodLabel
import bb.produce.invoicing.ui.common.paymentBadge
import bb.produce.invoicing.ui.common.printBadge
import bb.produce.invoicing.ui.common.syncBadge
import bb.produce.invoicing.ui.common.timeLabel
import bb.produce.invoicing.ui.draft.collectAsStateWithLifecycleCompat

/** Invoice list; on wide screens (tablet landscape) list and detail are shown side by side. */
@Composable
fun InvoicesScreen(session: Session, onOpen: (InvoiceId) -> Unit, onPreview: (InvoiceId) -> Unit) {
    val c = LocalAppContainer.current
    val vm: InvoiceListViewModel = viewModel(key = "invoices-${session.actor.userId}") { InvoiceListViewModel(c, session) }
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.then(if (wide) Modifier.width(420.dp) else Modifier.weight(1f)).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (session.isOwner) "All invoices" else "My invoices", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(ui.query, vm::setQuery, label = { Text("Search number, office ref, customer") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("invoice-search"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InvoiceFilter.entries.forEach { f -> FilterChip(selected = ui.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }) }
                }
                when {
                    ui.loading -> Unit
                    ui.totalCount == 0 -> EmptyState("No invoices yet", "Issued invoices appear here, newest first.")
                    ui.rows.isEmpty() -> EmptyState("No matches", "Try another search or filter.")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ui.rows, key = { it.id.toString() }) { row ->
                            InvoiceRowCard(row, session, selected = wide && selected == row.id.toString()) {
                                if (wide) selected = row.id.toString() else onOpen(row.id)
                            }
                        }
                    }
                }
            }
            if (wide) {
                VerticalDivider()
                val sel = selected
                if (sel == null) {
                    EmptyState("Select an invoice", "Details, payment status and the 58 mm preview appear here.")
                } else {
                    InvoiceDetailContent(session, InvoiceId(java.util.UUID.fromString(sel)), onPreview, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InvoiceRowCard(row: InvoiceListItem, session: Session, selected: Boolean, onClick: () -> Unit) {
    PiCard(Modifier.clickable(onClick = onClick).testTag("invoice-row-${row.number}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.number, style = MaterialTheme.typography.titleMedium)
                Text(row.customerName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    timeLabel(row.issuedAtDevice) + (if (session.isOwner) " · ${row.driverName}" else "") +
                        (row.registerReference?.let { " · Office ref $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(row.total.format(), style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            BadgeRow(issueBadge(row.voided), paymentBadge(row.paymentStatus), syncBadge(row.syncState), printBadge(row.printStatus))
        }
    }
}

@Composable
fun InvoiceDetailContent(session: Session, id: InvoiceId, onPreview: (InvoiceId) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val vm: InvoiceDetailViewModel = viewModel(key = "invoice-$id-${session.actor.userId}") { InvoiceDetailViewModel(c, session, id) }
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    val snackbar = remember { SnackbarHostState() }
    var payDialog by remember { mutableStateOf(false) }
    var reverseDialog by remember { mutableStateOf(false) }
    var voidDialog by remember { mutableStateOf(false) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Column(modifier.fillMaxSize()) {
        SnackbarHost(snackbar)
        if (ui.loading) return@Column
        val inv = ui.invoice
        if (inv == null) {
            EmptyState("Invoice not available", "It does not exist on this device or belongs to another driver.")
            return@Column
        }
        val ledger = ui.ledger
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Invoice")
            Text(inv.number.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("invoice-number"))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                BadgeRow(
                    issueBadge(inv.status == InvoiceStatus.VOIDED),
                    paymentBadge(ledger?.status ?: PaymentStatus.UNPAID, ledger?.paidMethod),
                    syncBadge(ui.syncState),
                    printBadge(ui.printStatus),
                )
            }
            PiCard {
                KeyValue("Driver", inv.driver.displayName)
                KeyValue("Customer", inv.customer.name)
                KeyValue("Issued (device time)", timeLabel(inv.issuedAtDevice))
                KeyValue(
                    "Office reference (internal)",
                    inv.registerReference?.text ?: "Not assigned yet",
                )
                inv.registeredAtServer?.let { KeyValue("Registered by office", timeLabel(it)) }
                if (inv.registerReference == null) {
                    Text("The office assigns its internal reference when this invoice first syncs. The invoice number above never changes.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (inv.deviceClockAheadOfServer()) Notice("Device clock was ahead of the office server when this invoice was issued.", Tone.WARN)
            }
            inv.voidRecord?.let { Notice("Void: ${it.reason} (${timeLabel(it.at)})", Tone.BAD) }
            PiCard {
                SectionLabel("Items")
                inv.lines.forEach { l ->
                    Text(l.description + if (l.unitPrice.isZero) " (no charge)" else "", fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth()) {
                        Text("${l.quantity} ${l.unit} × ${l.unitPrice.money.formatAmount()}", Modifier.weight(1f))
                        Text(l.lineTotal.formatAmount())
                    }
                    HorizontalDivider()
                }
                KeyValue("Total", inv.total.format())
                Text("Tax not applied (no tax treatment approved yet).", style = MaterialTheme.typography.bodySmall)
            }
            PiCard {
                SectionLabel("Payment")
                Text("Record how the customer paid. This app does not process card or bank payments.", style = MaterialTheme.typography.bodySmall)
                ledger?.events?.forEach { e ->
                    Text("${timeLabel(e.at)} · ${if (e.kind.name == "MARKED_PAID") "Marked paid" else "Reversed"} · ${methodLabel(e.method)}" +
                        (e.externalReference?.let { " · ref $it" } ?: "") + (e.reason?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inv.status == InvoiceStatus.ISSUED && ledger?.status == PaymentStatus.UNPAID) {
                        Button(onClick = { payDialog = true }, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mark paid…") }
                    }
                    if (session.isOwner && ledger?.status == PaymentStatus.PAID) {
                        OutlinedButton(onClick = { reverseDialog = true }, enabled = !ui.busy) { Text("Correct: mark unpaid…") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPreview(inv.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("58 mm print preview") }
                if (session.isOwner && inv.status == InvoiceStatus.ISSUED) {
                    TextButton(onClick = { voidDialog = true }, enabled = !ui.busy) { Text("Void…", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text("Invoice UUID ${inv.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (payDialog) PayDialog(vm.enabledMethods, ui.invoice?.total?.format() ?: "", onConfirm = { m, ref -> payDialog = false; vm.markPaid(m, ref) }, onDismiss = { payDialog = false })
    if (reverseDialog) ReasonDialog("Mark as unpaid?", "Adds a correcting entry. The original payment entry is kept.", "Mark unpaid", true,
        onConfirm = { reverseDialog = false; vm.reversePaid(it) }, onDismiss = { reverseDialog = false })
    if (voidDialog) ReasonDialog("Void this invoice?", "The invoice stays on record as VOID and its number is never reused. This cannot be undone.",
        "Void invoice", true, onConfirm = { voidDialog = false; vm.void(it) }, onDismiss = { voidDialog = false })
}

@Composable
private fun PayDialog(methods: List<PaymentMethod>, total: String, onConfirm: (PaymentMethod, String?) -> Unit, onDismiss: () -> Unit) {
    var method by remember { mutableStateOf<PaymentMethod?>(null) }
    var ref by remember { mutableStateOf("") }
    FormDialog("Mark paid: $total", onDismiss, "Record payment", confirmEnabled = method != null, onConfirm = { method?.let { onConfirm(it, ref) } }) {
        Text("Full amount only. Choose how the customer paid:")
        methods.forEach { m ->
            Row(Modifier.fillMaxWidth().selectable(selected = method == m, onClick = { method = m }, role = Role.RadioButton).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = method == m, onClick = null)
                Text(methodLabel(m))
            }
        }
        OutlinedTextField(ref, { ref = it }, label = { Text("Reference (optional, e.g. cheque no.)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Never enter card numbers. This records a fact; it does not take or verify a payment.", style = MaterialTheme.typography.bodySmall)
    }
}

@Suppress("unused")
private val listPadding = PaddingValues(16.dp)
