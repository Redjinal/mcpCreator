package bb.produce.invoicing.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bb.produce.invoicing.AppContainer
import bb.produce.invoicing.core.data.InvoiceListItem
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.payment.PaymentLedger
import bb.produce.invoicing.domain.payment.PaymentMethod
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.domain.printing.PrintStatus
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.Messages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class InvoiceFilter(val label: String) { ALL("All"), UNPAID("Unpaid"), NOT_SYNCED("Not synced"), VOID("Void") }

data class InvoiceListUi(
    val query: String = "",
    val filter: InvoiceFilter = InvoiceFilter.ALL,
    val rows: List<InvoiceListItem> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
)

class InvoiceListViewModel(c: AppContainer, session: Session) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(InvoiceFilter.ALL)

    val ui: StateFlow<InvoiceListUi> = combine(c.readModel.invoiceRows(session.actor, session.businessId), query, filter) { all, q, f ->
        val rows = all.filter { it.matches(q) }.filter {
            when (f) {
                InvoiceFilter.ALL -> true
                InvoiceFilter.UNPAID -> !it.voided && it.paymentStatus == PaymentStatus.UNPAID
                InvoiceFilter.NOT_SYNCED -> it.syncState != SyncState.SYNCED
                InvoiceFilter.VOID -> it.voided
            }
        }
        InvoiceListUi(q, f, rows, all.size, loading = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InvoiceListUi())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: InvoiceFilter) { filter.value = f }
}

data class InvoiceDetailUi(
    val loading: Boolean = true,
    val invoice: IssuedInvoice? = null,
    val ledger: PaymentLedger? = null,
    val printStatus: PrintStatus = PrintStatus.NOT_PRINTED,
    val syncState: SyncState = SyncState.PENDING,
    val notVisible: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
)

class InvoiceDetailViewModel(
    private val c: AppContainer,
    private val session: Session,
    private val id: InvoiceId,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    private val state = MutableStateFlow(InvoiceDetailUi())
    val ui: StateFlow<InvoiceDetailUi> = state.asStateFlow()

    /** MVP: every method except NONE. Which methods the business accepts is an open client decision (docs/08 gate 2). */
    val enabledMethods: List<PaymentMethod> = PaymentMethod.entries.filter { it != PaymentMethod.NONE }

    init {
        viewModelScope.launch { c.readModel.observeInvoiceChanges(id).collect { reload() } }
    }

    private suspend fun reload() {
        val inv = c.readModel.invoiceFor(session.actor, c.repository, id)
        val ledger = inv?.let { c.repository.paymentLedger(it.id) }
        val sync = inv?.let { c.repository.syncState(it.id) } ?: SyncState.PENDING
        val attempts = inv?.let { c.database.printDao().attempts(it.id.toString()) }.orEmpty()
        val print = when {
            attempts.any { it.status == PrintStatus.CONFIRMED.name } -> PrintStatus.CONFIRMED
            attempts.isNotEmpty() -> PrintStatus.valueOf(attempts.maxBy { it.updatedAt }.status)
            else -> PrintStatus.NOT_PRINTED
        }
        state.update { it.copy(loading = false, invoice = inv, ledger = ledger, notVisible = inv == null, syncState = sync, printStatus = print) }
    }

    fun markPaid(method: PaymentMethod, reference: String?) = act { inv, ledger ->
        when (val ev = ledger.markPaid(session.actor, method, inv.total, clock(), enabledMethods.toSet(), reference)) {
            is Outcome.Err -> Messages.payment(ev.error)
            is Outcome.Ok -> when (val r = c.repository.recordPayment(ev.value, session.actor)) {
                is Outcome.Ok -> "Recorded as paid (${bb.produce.invoicing.ui.common.methodLabel(method)}). No payment was processed by this app."
                is Outcome.Err -> Messages.payment(r.error)
            }
        }
    }

    fun reversePaid(reason: String) = act { _, ledger ->
        when (val ev = ledger.reversePaid(session.actor, reason, clock())) {
            is Outcome.Err -> Messages.payment(ev.error)
            is Outcome.Ok -> when (val r = c.repository.recordPayment(ev.value, session.actor)) {
                is Outcome.Ok -> "Payment entry reversed. Both entries stay in the audit trail."
                is Outcome.Err -> Messages.payment(r.error)
            }
        }
    }

    fun void(reason: String) = act { inv, _ ->
        when (val r = c.repository.void(inv.id, session.actor, reason)) {
            is Outcome.Ok -> "Invoice ${inv.number} is now void. Its number stays reserved."
            is Outcome.Err -> Messages.void(r.error)
        }
    }

    fun consumeMessage() = state.update { it.copy(message = null) }

    private fun act(block: suspend (IssuedInvoice, PaymentLedger) -> String) {
        val inv = state.value.invoice ?: return
        val ledger = state.value.ledger ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val msg = block(inv, ledger)
            reload()
            state.update { it.copy(busy = false, message = msg) }
        }
    }
}
