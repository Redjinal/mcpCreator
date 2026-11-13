package bb.produce.invoicing.ui.draft

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bb.produce.invoicing.AppContainer
import bb.produce.invoicing.core.data.IssueRejection
import bb.produce.invoicing.core.data.IssueResult
import bb.produce.invoicing.core.data.ShiftContext
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.invoice.DraftId
import bb.produce.invoicing.domain.invoice.DraftLine
import bb.produce.invoicing.domain.invoice.DraftProblem
import bb.produce.invoicing.domain.invoice.DraftValidator
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.invoice.IssueError
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import bb.produce.invoicing.domain.stock.StockBalance
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.Messages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class LineUi(
    val line: DraftLine,
    val product: Product?,
    val qtyText: String,
    val qtyError: String?,
    val priceText: String,
    val priceError: String?,
    val priceNote: String?,
    val stockNote: String?,
    val problems: List<String>,
)

data class DraftUi(
    val loading: Boolean = true,
    val draft: InvoiceDraft? = null,
    val customer: Customer? = null,
    val lines: List<LineUi> = emptyList(),
    val total: Money = Money.zero(),
    val problems: List<DraftProblem> = emptyList(),
    val showProblems: Boolean = false,
    val customers: List<Customer> = emptyList(),
    val products: List<Product> = emptyList(),
    val stock: Map<ProductId, StockBalance> = emptyMap(),
    val conversions: ConversionTable = ConversionTable(emptyList()),
    val shift: ShiftContext? = null,
    val cannotIssueReason: String? = null,
    val savedLocally: Boolean = false,
    val issuing: Boolean = false,
    val message: String? = null,
    val issuedInvoiceId: InvoiceId? = null,
) {
    val canIssue: Boolean get() = cannotIssueReason == null && !issuing
}

private data class Local(
    val draft: InvoiceDraft? = null,
    val qtyText: Map<LineId, String> = emptyMap(),
    val priceText: Map<LineId, String> = emptyMap(),
    val showProblems: Boolean = false,
    val savedLocally: Boolean = false,
    val issuing: Boolean = false,
    val message: String? = null,
    val issued: InvoiceId? = null,
    val shift: ShiftContext? = null,
    val cannotIssueReason: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DraftEditorViewModel(
    private val c: AppContainer,
    private val session: Session,
    draftIdArg: String?,
    presetCustomerId: String?,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private val saveMutex = Mutex()
    private val shiftFlow = MutableStateFlow<ShiftContext?>(null)

    private val catalogue = combine(
        c.readModel.customers(session.businessId),
        c.readModel.products(session.businessId),
        c.readModel.conversions(),
        shiftFlow.flatMapLatest { s -> s?.let { c.readModel.stockBalances(it.vehicle) } ?: flowOf(emptyMap()) },
    ) { customers, products, conversions, stock -> Catalogue(customers, products, conversions, stock) }

    private data class Catalogue(val customers: List<Customer>, val products: List<Product>, val conversions: ConversionTable, val stock: Map<ProductId, StockBalance>)

    val ui: StateFlow<DraftUi> = combine(local, catalogue) { l, cat -> build(l, cat) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DraftUi())

    init {
        viewModelScope.launch {
            val shift = c.readModel.shiftContext(session.actor)
            shiftFlow.value = shift
            val lease = c.readModel.issuingLease(session.actor)
            val reason = when {
                lease == null -> "You have no invoice-number allocation on this device, so you can prepare drafts but not issue them."
                lease.revoked -> "Your invoice-number allocation was revoked. Issuing is blocked."
                shift == null -> "No open shift on this device. Issuing needs an open shift with loaded stock."
                else -> null
            }
            val draft = draftIdArg?.let { c.repository.loadDraft(DraftId(UUID.fromString(it))) }
                ?: InvoiceDraft.start(
                    session.businessId, session.actor.userId, CatalogueVersion(c.readModel.catalogueVersion() ?: 1), now(),
                ).withCustomer(presetCustomerId?.let { CustomerId(UUID.fromString(it)) }, now())
            local.value = Local(
                draft = draft,
                qtyText = draft.lines.associate { it.id to (it.quantity?.toPlainString() ?: "") },
                priceText = draft.lines.associate { it.id to (it.unitPrice?.money?.toDecimal()?.toPlainString() ?: "") },
                savedLocally = draftIdArg != null,
                shift = shift,
                cannotIssueReason = reason,
            )
        }
    }

    private fun now() = clock()

    // ------------------------------------------------------------------ edits (each one auto-saves)

    fun setCustomer(id: CustomerId) = edit { it.withCustomer(id, now()) }

    fun addProduct(p: Product, unit: UnitCode) {
        val before = local.value.draft ?: return
        when (val r = before.addProduct(p, unit, now())) {
            is Outcome.Ok -> {
                val d = r.value
                val changed = d.lines.first { nl -> before.lines.none { it.id == nl.id } || before.lines.first { it.id == nl.id } != nl }
                local.update { l ->
                    l.copy(draft = d,
                        qtyText = l.qtyText + (changed.id to (changed.quantity?.toPlainString() ?: "")),
                        priceText = l.priceText + (changed.id to (changed.unitPrice?.money?.toDecimal()?.toPlainString() ?: "")))
                }
                persist()
            }
            is Outcome.Err -> local.update { it.copy(message = "Could not add ${p.name}: ${r.error}") }
        }
    }

    fun addCustomLine(description: String, unit: UnitCode) {
        val d = local.value.draft?.addCustomLine(description, unit, now()) ?: return
        val line = d.lines.last()
        local.update { it.copy(draft = d, qtyText = it.qtyText + (line.id to "1"), priceText = it.priceText + (line.id to "")) }
        persist()
    }

    fun setQuantityText(id: LineId, raw: String) {
        val text = raw.replace(',', '.')
        local.update { it.copy(qtyText = it.qtyText + (id to text)) }
        val q = (Quantity.parse(text) as? Outcome.Ok)?.value
        edit { it.updateQuantity(id, q, now()).getOrThrow() }
    }

    fun stepQuantity(id: LineId, delta: Int) {
        val line = local.value.draft?.lines?.firstOrNull { it.id == id } ?: return
        val next = (line.quantity?.value ?: BigDecimal.ZERO) + BigDecimal(delta)
        if (next.signum() <= 0) return
        setQuantityText(id, next.stripTrailingZeros().toPlainString())
    }

    fun setPriceText(id: LineId, raw: String) {
        val text = raw.replace(',', '.')
        local.update { it.copy(priceText = it.priceText + (id to text)) }
        val p = (UnitPrice.parse(text) as? Outcome.Ok)?.value
        edit { it.updateUnitPrice(id, p, now()).getOrThrow() }
    }

    fun setUnit(id: LineId, unit: UnitCode, product: Product?) {
        val d = local.value.draft ?: return
        when (val r = d.changeUnit(id, unit, product, now())) {
            is Outcome.Ok -> {
                val line = r.value.lines.first { it.id == id }
                local.update { it.copy(draft = r.value, priceText = it.priceText + (id to (line.unitPrice?.money?.toDecimal()?.toPlainString() ?: ""))) }
                persist()
            }
            is Outcome.Err -> local.update { it.copy(message = "That unit is not sold for this product.") }
        }
    }

    fun confirmZeroPrice(id: LineId, confirmed: Boolean) = edit { it.confirmZeroPrice(id, confirmed, now()).getOrThrow() }
    fun setDescription(id: LineId, text: String) = edit { it.updateDescription(id, text, now()).let { r -> if (r is Outcome.Ok) r.value else it } }

    /** Returns the removed line and its index so the screen can offer Undo. */
    fun removeLine(id: LineId): Pair<DraftLine, Int>? {
        val d = local.value.draft ?: return null
        val index = d.lines.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val line = d.lines[index]
        edit { it.removeLine(id, now()).getOrThrow() }
        return line to index
    }

    fun undoRemove(line: DraftLine, index: Int) = edit { d ->
        if (d.lines.any { it.id == line.id }) d
        else d.copy(lines = d.lines.toMutableList().apply { add(index.coerceIn(0, size), line) }, updatedAt = now())
    }

    fun consumeMessage() = local.update { it.copy(message = null) }
    fun consumeIssued() = local.update { it.copy(issued = null) }
    fun revealProblems() = local.update { it.copy(showProblems = true) }

    private fun edit(f: (InvoiceDraft) -> InvoiceDraft) {
        local.update { l -> l.draft?.let { l.copy(draft = f(it)) } ?: l }
        persist()
    }

    /** Saves the LATEST draft under a mutex, so out-of-order saves cannot overwrite newer edits. */
    private fun persist() {
        viewModelScope.launch {
            saveMutex.withLock {
                val d = local.value.draft ?: return@withLock
                when (c.repository.saveDraft(d)) {
                    is Outcome.Ok -> local.update { it.copy(savedLocally = true) }
                    is Outcome.Err -> local.update { it.copy(message = "This draft was already issued; it can no longer be edited.") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ issue

    fun issue() {
        val l = local.value
        val d = l.draft ?: return
        if (l.issuing) return // double-tap guard (the repository is idempotent per draft as well)
        local.update { it.copy(issuing = true, showProblems = true, message = null) }
        viewModelScope.launch {
            val result = saveMutex.withLock {
                c.repository.saveDraft(d)
                c.repository.issue(d.id, session.actor)
            }
            local.update {
                when (result) {
                    is IssueResult.Issued -> it.copy(issuing = false, issued = result.invoice.id)
                    is IssueResult.AlreadyIssued -> it.copy(issuing = false, issued = result.invoice.id)
                    is IssueResult.Rejected -> it.copy(issuing = false, message = Messages.issue(result.reason),
                        showProblems = it.showProblems || (result.reason as? IssueRejection.Domain)?.error is IssueError.Invalid)
                }
            }
        }
    }

    // ------------------------------------------------------------------ view state

    private fun build(l: Local, cat: Catalogue): DraftUi {
        val d = l.draft ?: return DraftUi(loading = true)
        val products = cat.products.associateBy { it.id }
        val problems = DraftValidator.problems(d, products, priceOverrideAllowed = false)

        // Stock needed per product across all lines, in base units.
        val needed = mutableMapOf<ProductId, BigDecimal>()
        for (line in d.lines) {
            val pid = line.productId ?: continue
            val q = line.quantity ?: continue
            val conv = cat.conversions.find(pid, line.unit) ?: continue
            needed[pid] = (needed[pid] ?: BigDecimal.ZERO) + conv.toBase(q).value
        }

        val lines = d.lines.map { line ->
            val product = line.productId?.let { products[it] }
            val qtyText = l.qtyText[line.id] ?: line.quantity?.toPlainString().orEmpty()
            val priceText = l.priceText[line.id] ?: line.unitPrice?.money?.toDecimal()?.toPlainString().orEmpty()
            val stockNote = line.productId?.let { pid ->
                val conv = cat.conversions.find(pid, line.unit)
                val bal = cat.stock[pid]
                when {
                    conv == null -> "No stock conversion for ${line.unit}; issuing will be refused."
                    bal == null -> "No stock of this product on the vehicle."
                    (needed[pid] ?: BigDecimal.ZERO) > bal.saleable ->
                        "Vehicle has ${bal.saleable.stripTrailingZeros().toPlainString()} ${conv.baseUnit}; this invoice needs " +
                            "${needed[pid]!!.stripTrailingZeros().toPlainString()} ${conv.baseUnit}."
                    else -> null
                }
            }
            LineUi(
                line = line,
                product = product,
                qtyText = qtyText,
                qtyError = if (line.quantity == null) Quantity.parse(qtyText).errorOrNull()?.let(Messages::quantity) else null,
                priceText = priceText,
                priceError = if (line.unitPrice == null && (priceText.isNotBlank() || l.showProblems)) {
                    UnitPrice.parse(priceText).errorOrNull()?.let(Messages::price)
                } else null,
                priceNote = when {
                    line.catalogueUnitPrice == null && line.productId != null -> "Price entered on invoice"
                    line.overridesCataloguePrice -> "Catalogue price ${line.catalogueUnitPrice!!.money.format()}. Changing it needs owner approval."
                    else -> null
                },
                stockNote = stockNote,
                problems = problems.filter { it.lineId == line.id }.map { Messages.problem(it.code) },
            )
        }
        return DraftUi(
            loading = false,
            draft = d,
            customer = d.customerId?.let { id -> cat.customers.firstOrNull { it.id == id } },
            lines = lines,
            total = d.runningTotal,
            problems = problems,
            showProblems = l.showProblems,
            customers = cat.customers,
            products = cat.products.filter { it.active },
            stock = cat.stock,
            conversions = cat.conversions,
            shift = l.shift,
            cannotIssueReason = l.cannotIssueReason,
            savedLocally = l.savedLocally,
            issuing = l.issuing,
            message = l.message,
            issuedInvoiceId = l.issued,
        )
    }
}
