package bb.produce.invoicing.domain.payment

import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.invoice.InvoiceStatus
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.money.Money
import java.time.Instant

/*
 * Payment model (docs/09): a RECORDED FACT, separate from the invoice. No gateway, no bank
 * verification, no card data. MVP = full-amount, single-method settlement; no partial/mixed.
 * Payment amounts are never added to sales totals.
 */

enum class PaymentStatus { UNPAID, PAID }

enum class PaymentMethod {
    CASH, BANK_TRANSFER, CHEQUE,
    /** Card taken on an external terminal. The app never processes or stores card data. */
    CARD_EXTERNAL,
    OTHER,
    NONE,
}

enum class PaymentEventKind { MARKED_PAID, PAID_REVERSED }

/** Append-only payment event. A correction is a new [PaymentEventKind.PAID_REVERSED] event, never an edit/delete. */
data class PaymentEvent(
    val id: EventId,
    val invoiceId: InvoiceId,
    val kind: PaymentEventKind,
    val method: PaymentMethod,
    val amount: Money,
    val recordedBy: UserId,
    val deviceId: DeviceId,
    val at: Instant,
    val externalReference: String? = null,
    val reversesEventId: EventId? = null,
    val reason: String? = null,
) {
    init {
        require(amount.minor > 0) { "Payment event amount must be positive" }
        when (kind) {
            PaymentEventKind.MARKED_PAID -> {
                require(method != PaymentMethod.NONE) { "PAID requires a method" }
                require(reversesEventId == null) { "MARKED_PAID cannot reverse an event" }
            }
            PaymentEventKind.PAID_REVERSED -> {
                require(reversesEventId != null) { "Reversal must reference the PAID event" }
                require(!reason.isNullOrBlank()) { "Reversal requires a reason" }
            }
        }
    }
}

enum class PaymentError {
    ALREADY_PAID,
    NOT_PAID,
    INVOICE_VOIDED,
    METHOD_REQUIRED,
    METHOD_NOT_ENABLED,
    /** Partial or over-payment: MVP requires amount == outstanding. */
    AMOUNT_MUST_EQUAL_OUTSTANDING,
    NOT_AUTHORIZED,
    REASON_REQUIRED,
    UNKNOWN_EVENT,
    /** External reference looks like a payment card number or is too long. */
    EXTERNAL_REFERENCE_REJECTED,
    /** Same event id already recorded with different content. */
    EVENT_ID_CONFLICT,
}

/**
 * Payment state of one invoice, derived only from its events. [record] is idempotent by event id,
 * so replaying an upload 10× yields one settlement (Task 10 gate).
 */
class PaymentLedger private constructor(
    val invoiceId: InvoiceId,
    private val invoiceTotal: Money,
    private val invoiceVoided: Boolean,
    private val invoiceDriver: UserId,
    val events: List<PaymentEvent>,
) {
    /** The PAID event currently in force, if any. */
    val activePaidEvent: PaymentEvent?
        get() {
            val reversed = events.filter { it.kind == PaymentEventKind.PAID_REVERSED }.mapNotNull { it.reversesEventId }.toSet()
            return events.lastOrNull { it.kind == PaymentEventKind.MARKED_PAID && it.id !in reversed }
        }

    val status: PaymentStatus get() = if (activePaidEvent != null) PaymentStatus.PAID else PaymentStatus.UNPAID
    val paidMethod: PaymentMethod? get() = activePaidEvent?.method
    val paidAmount: Money get() = activePaidEvent?.amount ?: Money.zero()
    val outstanding: Money get() = if (status == PaymentStatus.PAID) Money.zero() else invoiceTotal

    fun markPaid(
        actor: Actor,
        method: PaymentMethod,
        amount: Money,
        at: Instant,
        enabledMethods: Set<PaymentMethod>,
        externalReference: String? = null,
        eventId: EventId = EventId.random(),
    ): Outcome<PaymentEvent, PaymentError> {
        if (!actor.isOwner && actor.userId != invoiceDriver) return Outcome.Err(PaymentError.NOT_AUTHORIZED)
        if (invoiceVoided) return Outcome.Err(PaymentError.INVOICE_VOIDED)
        if (status == PaymentStatus.PAID) return Outcome.Err(PaymentError.ALREADY_PAID)
        if (method == PaymentMethod.NONE) return Outcome.Err(PaymentError.METHOD_REQUIRED)
        if (method !in enabledMethods) return Outcome.Err(PaymentError.METHOD_NOT_ENABLED)
        if (amount != outstanding) return Outcome.Err(PaymentError.AMOUNT_MUST_EQUAL_OUTSTANDING)
        val ref = externalReference?.trim()?.takeIf { it.isNotEmpty() }
        if (ref != null && !ExternalReference.isAcceptable(ref)) return Outcome.Err(PaymentError.EXTERNAL_REFERENCE_REJECTED)
        return Outcome.Ok(
            PaymentEvent(eventId, invoiceId, PaymentEventKind.MARKED_PAID, method, amount, actor.userId, actor.deviceId, at, ref),
        )
    }

    /**
     * Corrects a mistaken PAID by appending a reversal. PROVISIONAL policy: owner only (client to confirm
     * whether drivers may reverse their own same-shift entries).
     */
    fun reversePaid(actor: Actor, reason: String, at: Instant, eventId: EventId = EventId.random()): Outcome<PaymentEvent, PaymentError> {
        if (!actor.isOwner) return Outcome.Err(PaymentError.NOT_AUTHORIZED)
        if (reason.isBlank()) return Outcome.Err(PaymentError.REASON_REQUIRED)
        val paid = activePaidEvent ?: return Outcome.Err(PaymentError.NOT_PAID)
        return Outcome.Ok(
            PaymentEvent(eventId, invoiceId, PaymentEventKind.PAID_REVERSED, paid.method, paid.amount, actor.userId, actor.deviceId, at,
                reversesEventId = paid.id, reason = reason.trim()),
        )
    }

    /** Appends an event. Re-recording the identical event is a no-op (idempotent replay). */
    fun record(event: PaymentEvent): Outcome<PaymentLedger, PaymentError> {
        require(event.invoiceId == invoiceId) { "Event for another invoice" }
        val existing = events.firstOrNull { it.id == event.id }
        if (existing != null) return if (existing == event) Outcome.Ok(this) else Outcome.Err(PaymentError.EVENT_ID_CONFLICT)
        when (event.kind) {
            PaymentEventKind.MARKED_PAID -> {
                if (invoiceVoided) return Outcome.Err(PaymentError.INVOICE_VOIDED)
                if (status == PaymentStatus.PAID) return Outcome.Err(PaymentError.ALREADY_PAID)
                if (event.amount != invoiceTotal) return Outcome.Err(PaymentError.AMOUNT_MUST_EQUAL_OUTSTANDING)
            }
            PaymentEventKind.PAID_REVERSED ->
                if (activePaidEvent?.id != event.reversesEventId) return Outcome.Err(PaymentError.UNKNOWN_EVENT)
        }
        return Outcome.Ok(PaymentLedger(invoiceId, invoiceTotal, invoiceVoided, invoiceDriver, events + event))
    }

    companion object {
        fun forInvoice(invoice: IssuedInvoice, events: List<PaymentEvent> = emptyList()): PaymentLedger {
            var ledger = PaymentLedger(invoice.id, invoice.total, false, invoice.driver.userId, emptyList())
            for (e in events) ledger = ledger.record(e).getOrThrow()
            // Voiding after payment keeps the history; new PAID events are blocked from then on.
            return PaymentLedger(invoice.id, invoice.total, invoice.status == InvoiceStatus.VOIDED, invoice.driver.userId, ledger.events)
        }
    }
}

object ExternalReference {
    const val MAX_LENGTH = 40
    private val DIGIT_RUN = Regex("""\d(?:[ -]?\d)+""")

    /** Rejects over-long references and anything containing a Luhn-valid 13–19 digit run (likely a card number). */
    fun isAcceptable(ref: String): Boolean {
        if (ref.length > MAX_LENGTH) return false
        // Digit runs, allowing single spaces/hyphens between digits (how card numbers are usually written).
        val runs = DIGIT_RUN.findAll(ref).map { m -> m.value.filter(Char::isDigit) }
        // Whole runs only: checking every sub-window would falsely reject ~10% of long bank references.
        return runs.none { it.length in 13..19 && luhn(it) }
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}

/** Reporting totals (docs/09). Paid amounts are a breakdown of issued sales, never added to them. */
data class PaymentSummary(
    val invoiceCount: Int,
    val issuedGross: Money,
    val voidedGross: Money,
    val paidGrossByMethod: Map<PaymentMethod, Money>,
    val unpaidGross: Money,
) {
    val paidGross: Money get() = Money.sum(paidGrossByMethod.values)

    companion object {
        fun of(entries: List<Pair<IssuedInvoice, PaymentLedger>>): PaymentSummary {
            val active = entries.filter { it.first.status == InvoiceStatus.ISSUED }
            val voided = entries.filter { it.first.status == InvoiceStatus.VOIDED }
            val paid = active.filter { it.second.status == PaymentStatus.PAID }
            return PaymentSummary(
                invoiceCount = active.size,
                issuedGross = Money.sum(active.map { it.first.total }),
                voidedGross = Money.sum(voided.map { it.first.total }),
                paidGrossByMethod = paid.groupBy { requireNotNull(it.second.paidMethod) }
                    .mapValues { (_, v) -> Money.sum(v.map { it.second.paidAmount }) },
                unpaidGross = Money.sum(active.filter { it.second.status == PaymentStatus.UNPAID }.map { it.first.total }),
            )
        }
    }
}
