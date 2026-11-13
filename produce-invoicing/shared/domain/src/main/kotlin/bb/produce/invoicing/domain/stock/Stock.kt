package bb.produce.invoicing.domain.stock

import bb.produce.invoicing.domain.catalogue.BaseQuantity
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.money.UnitCode
import java.math.BigDecimal
import java.time.Instant

/*
 * Vehicle stock ledger (docs/09). Append-only, idempotent, exact decimals in each product's base unit.
 * Customer returns are QUARANTINED until an owner disposition; a physical return never edits an invoice
 * and is not a refund/credit.
 */

enum class StockMovementKind {
    OPENING_LOAD, TOP_UP, SALE_DEBIT, CUSTOMER_RETURN_IN,
    /**
     * Owner disposition "resellable": moves quantity from quarantine to saleable. Not in the docs/09 list;
     * added because the list has no event for releasing quarantine. Pending client confirmation (gate 4).
     */
    QUARANTINE_RELEASE,
    RETURN_TO_WAREHOUSE, SPOILAGE, COUNT_ADJUSTMENT, REVERSAL,
}

enum class StockBucket { SALEABLE, QUARANTINE }

/** Physical disposition of a returned item. Commercial remedy (credit/refund) is a separate, later workflow. */
enum class ReturnDisposition { QUARANTINE, RESELLABLE, SPOILAGE, SUPPLIER_RETURN }

/** Links a SALE_DEBIT to exactly one invoice line. Never a bare driver sequence or timestamp (docs/09 V3). */
data class SaleReference(val invoiceId: InvoiceId, val lineId: LineId)

data class StockMovement(
    val id: EventId,
    val shiftId: ShiftId,
    val locationId: LocationId,
    val productId: ProductId,
    val kind: StockMovementKind,
    val bucket: StockBucket,
    /** Positive for every kind except COUNT_ADJUSTMENT (signed) and REVERSAL (derived from the target). */
    val quantity: BaseQuantity,
    val recordedBy: Actor,
    val at: Instant,
    val saleReference: SaleReference? = null,
    val conversionVersion: Int? = null,
    val reversesMovementId: EventId? = null,
    val reason: String? = null,
) {
    init {
        val sign = quantity.value.signum()
        when (kind) {
            StockMovementKind.COUNT_ADJUSTMENT -> {
                require(sign != 0) { "Adjustment cannot be zero" }
                require(bucket == StockBucket.SALEABLE) { "Count adjustments apply to saleable stock" }
            }
            StockMovementKind.REVERSAL -> require(reversesMovementId != null) { "Reversal must reference a movement" }
            else -> require(sign > 0) { "$kind quantity must be positive" }
        }
        when (kind) {
            StockMovementKind.OPENING_LOAD, StockMovementKind.TOP_UP, StockMovementKind.SALE_DEBIT ->
                require(bucket == StockBucket.SALEABLE) { "$kind applies to saleable stock" }
            StockMovementKind.CUSTOMER_RETURN_IN, StockMovementKind.QUARANTINE_RELEASE ->
                require(bucket == StockBucket.QUARANTINE) { "$kind applies to quarantine" }
            else -> Unit
        }
        if (kind == StockMovementKind.SALE_DEBIT) require(saleReference != null) { "SALE_DEBIT needs its invoice line" }
        if (kind in REASON_REQUIRED) require(!reason.isNullOrBlank()) { "$kind requires a reason" }
    }

    companion object {
        val REASON_REQUIRED = setOf(StockMovementKind.SPOILAGE, StockMovementKind.COUNT_ADJUSTMENT, StockMovementKind.REVERSAL)
    }
}

/** Who may record which movement (docs/09, task 04 V2). Repeated in repository/API checks later. */
object StockPermissions {
    private val DRIVER_ALLOWED = setOf(
        StockMovementKind.SALE_DEBIT,
        StockMovementKind.CUSTOMER_RETURN_IN,
        StockMovementKind.SPOILAGE,
        StockMovementKind.RETURN_TO_WAREHOUSE,
    )

    fun canRecord(role: Role, kind: StockMovementKind): Boolean = role == Role.OWNER || kind in DRIVER_ALLOWED
}

/** Client gate 5 (unresolved): may saleable stock go below zero? Must be chosen explicitly. */
enum class NegativeStockPolicy { BLOCK, ALLOW_AND_FLAG }

enum class StockError {
    NOT_AUTHORIZED,
    DUPLICATE_SALE_DEBIT,
    EVENT_ID_CONFLICT,
    UNKNOWN_REVERSAL_TARGET,
    ALREADY_REVERSED,
    CANNOT_REVERSE_REVERSAL,
    INSUFFICIENT_STOCK,
    UNIT_MISMATCH,
    WRONG_LOCATION,
}

data class StockBalance(val productId: ProductId, val unit: UnitCode?, val saleable: BigDecimal, val quarantined: BigDecimal) {
    /** Variance for a physical count of SALEABLE stock: counted − expected (docs/09). */
    fun variance(counted: BigDecimal): BigDecimal = counted - saleable
}

/** Movements of one location (vehicle). Immutable; [append] returns a new ledger. */
class StockLedger private constructor(val locationId: LocationId, val movements: List<StockMovement>) {

    fun append(m: StockMovement, policy: NegativeStockPolicy): Outcome<StockLedger, StockError> {
        if (m.locationId != locationId) return Outcome.Err(StockError.WRONG_LOCATION)
        movements.firstOrNull { it.id == m.id }?.let { existing ->
            return if (existing == m) Outcome.Ok(this) else Outcome.Err(StockError.EVENT_ID_CONFLICT)
        }
        if (!StockPermissions.canRecord(m.recordedBy.role, m.kind)) return Outcome.Err(StockError.NOT_AUTHORIZED)
        if (m.kind == StockMovementKind.SALE_DEBIT &&
            movements.any { it.kind == StockMovementKind.SALE_DEBIT && it.saleReference == m.saleReference }
        ) return Outcome.Err(StockError.DUPLICATE_SALE_DEBIT)
        if (m.kind == StockMovementKind.REVERSAL) {
            val target = movements.firstOrNull { it.id == m.reversesMovementId } ?: return Outcome.Err(StockError.UNKNOWN_REVERSAL_TARGET)
            if (target.kind == StockMovementKind.REVERSAL) return Outcome.Err(StockError.CANNOT_REVERSE_REVERSAL)
            if (movements.any { it.reversesMovementId == target.id }) return Outcome.Err(StockError.ALREADY_REVERSED)
        }
        val unit = movements.firstOrNull { it.productId == m.productId }?.quantity?.unit
        if (unit != null && unit != m.quantity.unit) return Outcome.Err(StockError.UNIT_MISMATCH)

        val next = StockLedger(locationId, movements + m)
        if (policy == NegativeStockPolicy.BLOCK) {
            val b = next.balance(m.productId)
            if (b.saleable.signum() < 0 || b.quarantined.signum() < 0) return Outcome.Err(StockError.INSUFFICIENT_STOCK)
        }
        return Outcome.Ok(next)
    }

    fun balance(productId: ProductId): StockBalance {
        var saleable = BigDecimal.ZERO
        var quarantined = BigDecimal.ZERO
        val byId = movements.associateBy { it.id }
        fun applyEffect(m: StockMovement, sign: Int) {
            val q = m.quantity.value.multiply(BigDecimal(sign))
            when (m.kind) {
                StockMovementKind.OPENING_LOAD, StockMovementKind.TOP_UP, StockMovementKind.COUNT_ADJUSTMENT -> saleable += q
                StockMovementKind.SALE_DEBIT -> saleable -= q
                StockMovementKind.CUSTOMER_RETURN_IN -> quarantined += q
                StockMovementKind.QUARANTINE_RELEASE -> { quarantined -= q; saleable += q }
                StockMovementKind.RETURN_TO_WAREHOUSE, StockMovementKind.SPOILAGE ->
                    if (m.bucket == StockBucket.SALEABLE) saleable -= q else quarantined -= q
                StockMovementKind.REVERSAL -> Unit
            }
        }
        for (m in movements.filter { it.productId == productId }) {
            if (m.kind == StockMovementKind.REVERSAL) applyEffect(byId.getValue(requireNotNull(m.reversesMovementId)), -1)
            else applyEffect(m, +1)
        }
        val unit = movements.firstOrNull { it.productId == productId }?.quantity?.unit
        return StockBalance(productId, unit, saleable.stripTrailingZeros(), quarantined.stripTrailingZeros())
    }

    companion object {
        fun empty(locationId: LocationId) = StockLedger(locationId, emptyList())
    }
}

sealed interface SaleDebitError {
    data class MissingConversion(val lineId: LineId, val productId: ProductId, val unit: UnitCode) : SaleDebitError
}

/**
 * Builds the SALE_DEBIT movements for an issued invoice, one per catalogue line, converted to base units
 * with the explicit conversion in force. Custom (non-catalogue) lines are not stock-tracked.
 * These movements must be committed in the SAME transaction as the invoice (invariant 11).
 */
object SaleDebits {
    fun forInvoice(
        invoice: IssuedInvoice,
        conversions: ConversionTable,
        shiftId: ShiftId,
        locationId: LocationId,
        actor: Actor,
        idFor: (LineId) -> EventId = { EventId.random() },
    ): Outcome<List<StockMovement>, SaleDebitError> {
        val out = mutableListOf<StockMovement>()
        for (line in invoice.lines) {
            val productId = line.productId ?: continue
            val conv = conversions.find(productId, line.unit)
                ?: return Outcome.Err(SaleDebitError.MissingConversion(line.lineId, productId, line.unit))
            out += StockMovement(
                id = idFor(line.lineId),
                shiftId = shiftId,
                locationId = locationId,
                productId = productId,
                kind = StockMovementKind.SALE_DEBIT,
                bucket = StockBucket.SALEABLE,
                quantity = conv.toBase(line.quantity),
                recordedBy = actor,
                at = invoice.issuedAtDevice,
                saleReference = SaleReference(invoice.id, line.lineId),
                conversionVersion = conv.version,
            )
        }
        return Outcome.Ok(out)
    }
}
