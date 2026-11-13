package bb.produce.invoicing.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import bb.produce.invoicing.AppContainer
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.data.InvoiceListItem
import bb.produce.invoicing.core.data.ShiftContext
import bb.produce.invoicing.core.data.db.DraftRow
import bb.produce.invoicing.domain.common.BarbadosTime
import bb.produce.invoicing.domain.invoice.DraftId
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.EmptyState
import bb.produce.invoicing.ui.common.KeyValue
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.PiCard
import bb.produce.invoicing.ui.common.ReasonDialog
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.Tone
import bb.produce.invoicing.ui.common.timeLabel
import bb.produce.invoicing.ui.draft.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class DriverDay(val driverName: String, val count: Int, val total: Money, val paid: Money, val unpaid: Money)

data class HomeUi(
    val today: LocalDate,
    val todayIssued: Int = 0,
    val todayTotal: Money = Money.zero(),
    val todayPaid: Money = Money.zero(),
    val todayUnpaid: Money = Money.zero(),
    val notSynced: Int = 0,
    val waitingToSync: Int = 0,
    val drafts: List<DraftRow> = emptyList(),
    val byDriver: List<DriverDay> = emptyList(),
    val shift: ShiftContext? = null,
    val canIssue: Boolean = false,
)

class HomeViewModel(private val c: AppContainer, private val session: Session, private val clock: Clock = Clock.systemUTC()) : ViewModel() {
    private val shift = MutableStateFlow<ShiftContext?>(null)
    private val canIssue = MutableStateFlow(false)

    val ui: StateFlow<HomeUi> = combine(
        c.readModel.invoiceRows(session.actor, session.businessId),
        if (session.isOwner) flowOf(emptyList()) else c.readModel.draftRows(session.actor),
        c.readModel.pendingOutboxCount(),
        shift,
        canIssue,
    ) { rows, drafts, pending, s, issue ->
        val today = BarbadosTime.localDate(clock.instant())
        val todays = rows.filter { !it.voided && BarbadosTime.localDate(it.issuedAtDevice) == today }
        fun paid(l: List<InvoiceListItem>) = Money.sum(l.filter { it.paymentStatus == PaymentStatus.PAID }.map { it.total })
        fun unpaid(l: List<InvoiceListItem>) = Money.sum(l.filter { it.paymentStatus == PaymentStatus.UNPAID }.map { it.total })
        HomeUi(
            today = today,
            todayIssued = todays.size,
            todayTotal = Money.sum(todays.map { it.total }),
            todayPaid = paid(todays),
            todayUnpaid = unpaid(todays),
            notSynced = rows.count { it.syncState != SyncState.SYNCED },
            waitingToSync = pending,
            drafts = drafts,
            byDriver = todays.groupBy { it.driverName }.map { (name, l) -> DriverDay(name, l.size, Money.sum(l.map { it.total }), paid(l), unpaid(l)) }
                .sortedBy { it.driverName },
            shift = s,
            canIssue = issue,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi(BarbadosTime.localDate(clock.instant())))

    init {
        viewModelScope.launch {
            shift.value = c.readModel.shiftContext(session.actor)
            canIssue.value = c.readModel.issuingLease(session.actor)?.revoked == false
        }
    }

    fun discardDraft(id: String) = viewModelScope.launch { c.repository.discardDraft(DraftId(UUID.fromString(id))) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(session: Session, onNewInvoice: () -> Unit, onOpenDraft: (String) -> Unit, onOpenInvoices: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: HomeViewModel = viewModel(key = "home-${session.actor.userId}") { HomeViewModel(c, session) }
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    var discard by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(if (session.isOwner) "Owner" else "Driver ${session.driverCode ?: ""}")
        Text("Today · ${ui.today}", style = MaterialTheme.typography.headlineSmall)
        if (!session.isOwner) {
            ui.shift?.let { Notice("${it.vehicleName} · shift ${it.localDate} (${it.status.lowercase()})", Tone.NEUTRAL) }
                ?: Notice("No open shift on this device. You can prepare drafts, but issuing needs an open shift with loaded stock.", Tone.WARN)
            Button(onClick = onNewInvoice, enabled = ui.canIssue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("home-new-invoice")) {
                Text("+ New invoice")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Issued today", ui.todayIssued.toString())
            Stat("Sales today", ui.todayTotal.format())
            Stat("Recorded paid", ui.todayPaid.format())
            Stat("Unpaid", ui.todayUnpaid.format())
        }
        PiCard {
            SectionLabel("Sync")
            KeyValue("Invoices not synced", ui.notSynced.toString())
            KeyValue("Changes waiting to send", ui.waitingToSync.toString())
            Text("Office sync is not set up yet (Task 05). Everything is saved on this device; nothing has been sent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onOpenInvoices) { Text("View invoices") }
        }
        if (session.isOwner) {
            PiCard {
                SectionLabel("Today by driver (invoices on this device)")
                if (ui.byDriver.isEmpty()) Text("No invoices today.")
                ui.byDriver.forEach { d ->
                    Text(d.driverName, style = MaterialTheme.typography.titleSmall)
                    KeyValue("${d.count} issued", d.total.format())
                    KeyValue("Paid / unpaid", "${d.paid.formatAmount()} / ${d.unpaid.formatAmount()}")
                }
                Text("Only invoices on this tablet are shown until office sync exists (Task 05).", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            PiCard {
                SectionLabel("Drafts")
                if (ui.drafts.isEmpty()) EmptyState("No drafts", "Unfinished invoices are saved here automatically.")
                ui.drafts.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenDraft(d.id) }.padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.customerName ?: "No customer yet", style = MaterialTheme.typography.titleSmall)
                            Text("${d.lineCount} item(s) · saved ${timeLabel(java.time.Instant.ofEpochMilli(d.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onOpenDraft(d.id) }) { Text("Continue") }
                        TextButton(onClick = { discard = d.id }) { Text("Discard") }
                    }
                }
            }
        }
    }
    discard?.let { id ->
        ReasonDialog("Discard draft?", "The draft and its items are deleted from this device. Issued invoices are not affected.", "Discard", false,
            onConfirm = { vm.discardDraft(id); discard = null }, onDismiss = { discard = null })
    }
}

@Composable
private fun Stat(label: String, value: String) {
    PiCard(Modifier.widthIn(min = 150.dp, max = 260.dp)) {
        SectionLabel(label)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
