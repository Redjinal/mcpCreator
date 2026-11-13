package bb.produce.invoicing.domain.invoice

import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.money.LineMath
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmInline
value class DraftId(val value: UUID) {
    override fun toString() = value.toString()
    companion object { fun random() = DraftId(UUID.randomUUID()) }
}

/**
 * One editable line. `quantity`/`unitPrice` are null while the user's input is missing or invalid
 * (the UI keeps the raw text and shows the parse error). `catalogueUnitPrice` is the price from the
 * catalogue version in force when the line was added or its unit changed.
 */
data class DraftLine(
    val id: LineId,
    val productId: ProductId?,
    val description: String,
    val unit: UnitCode,
    val quantity: Quantity?,
    val unitPrice: UnitPrice?,
    val catalogueUnitPrice: UnitPrice?,
    val priceEdited: Boolean = false,
    val zeroPriceConfirmed: Boolean = false,
) {
    val isCustom: Boolean get() = productId == null

    /** Exact line total, or null while quantity/price are incomplete. */
    val lineTotal: Money?
        get() = if (quantity != null && unitPrice != null) LineMath.lineTotal(quantity, unitPrice) else null

    /** True when the price differs from a catalogue price (needs authorisation, D06). */
    val overridesCataloguePrice: Boolean
        get() = catalogueUnitPrice != null && unitPrice != null && unitPrice != catalogueUnitPrice
}

enum class DraftEditError { UNKNOWN_LINE, UNIT_NOT_ALLOWED_FOR_PRODUCT, PRODUCT_INACTIVE, NOT_A_CUSTOM_LINE, QUANTITY_LIMIT }

enum class DraftProblemCode {
    NO_CUSTOMER,
    NO_LINES,
    MISSING_DESCRIPTION,
    MISSING_QUANTITY,
    MISSING_PRICE,
    ZERO_PRICE_NOT_CONFIRMED,
    UNIT_NOT_ALLOWED,
    PRODUCT_NOT_IN_CATALOGUE,
    PRODUCT_INACTIVE,
    PRICE_OVERRIDE_NOT_AUTHORIZED,
    TOTAL_TOO_LARGE,
}

data class DraftProblem(val code: DraftProblemCode, val lineId: LineId? = null)

/**
 * An editable, auto-saved invoice draft. All edits return a new value; persistence is Task 02.
 * Drafts have no invoice number: `PRE-INV-SUF` is assigned only at issue.
 */
data class InvoiceDraft(
    val id: DraftId,
    val businessId: BusinessId,
    val driverUserId: UserId,
    val customerId: CustomerId?,
    val lines: List<DraftLine>,
    val catalogueVersion: CatalogueVersion,
    val updatedAt: Instant,
) {
    init {
        require(lines.map { it.id }.toSet().size == lines.size) { "Duplicate line id in draft" }
    }

    /** Sum of the lines that currently have a computable total. Display only; issue validates everything. */
    val runningTotal: Money get() = Money.sum(lines.mapNotNull { it.lineTotal })

    fun withCustomer(customerId: CustomerId?, at: Instant) = copy(customerId = customerId, updatedAt = at)

    /**
     * Adds a catalogue product in [unit]. As in v4, adding the same product+unit again increases that
     * line's quantity by 1 instead of creating a duplicate line.
     */
    fun addProduct(product: Product, unit: UnitCode, at: Instant, lineId: LineId = LineId.random()): Outcome<InvoiceDraft, DraftEditError> {
        if (!product.active) return Outcome.Err(DraftEditError.PRODUCT_INACTIVE)
        if (!product.allows(unit)) return Outcome.Err(DraftEditError.UNIT_NOT_ALLOWED_FOR_PRODUCT)
        val existing = lines.firstOrNull { it.productId == product.id && it.unit == unit }
        if (existing != null) {
            val current = existing.quantity?.value ?: BigDecimal.ZERO
            val next = Quantity.parse((current + BigDecimal.ONE).toPlainString())
            val q = (next as? Outcome.Ok)?.value ?: return Outcome.Err(DraftEditError.QUANTITY_LIMIT)
            return Outcome.Ok(replace(existing.id, at) { it.copy(quantity = q) })
        }
        val price = product.catalogueUnitPrice(unit)
        val line = DraftLine(
            id = lineId,
            productId = product.id,
            description = product.name,
            unit = unit,
            quantity = Quantity.of("1"),
            unitPrice = price,
            catalogueUnitPrice = price,
        )
        return Outcome.Ok(copy(lines = lines + line, updatedAt = at))
    }

    fun addCustomLine(description: String, unit: UnitCode, at: Instant, lineId: LineId = LineId.random()): InvoiceDraft =
        copy(
            lines = lines + DraftLine(lineId, null, description.trim(), unit, Quantity.of("1"), null, null, priceEdited = true),
            updatedAt = at,
        )

    fun updateQuantity(lineId: LineId, quantity: Quantity?, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        edit(lineId, at) { Outcome.Ok(it.copy(quantity = quantity)) }

    /** Any price edit clears a previous zero-price confirmation (v4 behaviour). */
    fun updateUnitPrice(lineId: LineId, price: UnitPrice?, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        edit(lineId, at) { Outcome.Ok(it.copy(unitPrice = price, priceEdited = true, zeroPriceConfirmed = false)) }

    fun confirmZeroPrice(lineId: LineId, confirmed: Boolean, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        edit(lineId, at) { Outcome.Ok(it.copy(zeroPriceConfirmed = confirmed)) }

    fun updateDescription(lineId: LineId, description: String, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        edit(lineId, at) {
            if (!it.isCustom) Outcome.Err(DraftEditError.NOT_A_CUSTOM_LINE) else Outcome.Ok(it.copy(description = description.trim()))
        }

    /**
     * Changes a line's unit. Quantity is NOT converted between units. For catalogue lines the unit must
     * be permitted, and the catalogue price for the new unit is applied unless the price was edited.
     */
    fun changeUnit(lineId: LineId, unit: UnitCode, product: Product?, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        edit(lineId, at) { line ->
            if (line.isCustom) return@edit Outcome.Ok(line.copy(unit = unit))
            if (product == null || product.id != line.productId || !product.allows(unit)) {
                return@edit Outcome.Err(DraftEditError.UNIT_NOT_ALLOWED_FOR_PRODUCT)
            }
            val catalogue = product.catalogueUnitPrice(unit)
            Outcome.Ok(
                line.copy(
                    unit = unit,
                    catalogueUnitPrice = catalogue,
                    unitPrice = if (line.priceEdited) line.unitPrice else catalogue,
                    zeroPriceConfirmed = if (line.priceEdited) line.zeroPriceConfirmed else false,
                ),
            )
        }

    fun removeLine(lineId: LineId, at: Instant): Outcome<InvoiceDraft, DraftEditError> =
        if (lines.none { it.id == lineId }) Outcome.Err(DraftEditError.UNKNOWN_LINE)
        else Outcome.Ok(copy(lines = lines.filterNot { it.id == lineId }, updatedAt = at))

    private fun edit(lineId: LineId, at: Instant, f: (DraftLine) -> Outcome<DraftLine, DraftEditError>): Outcome<InvoiceDraft, DraftEditError> {
        val line = lines.firstOrNull { it.id == lineId } ?: return Outcome.Err(DraftEditError.UNKNOWN_LINE)
        return when (val r = f(line)) {
            is Outcome.Ok -> Outcome.Ok(replace(lineId, at) { r.value })
            is Outcome.Err -> r
        }
    }

    private fun replace(lineId: LineId, at: Instant, f: (DraftLine) -> DraftLine) =
        copy(lines = lines.map { if (it.id == lineId) f(it) else it }, updatedAt = at)

    companion object {
        fun start(businessId: BusinessId, driver: UserId, catalogueVersion: CatalogueVersion, at: Instant, id: DraftId = DraftId.random()) =
            InvoiceDraft(id, businessId, driver, null, emptyList(), catalogueVersion, at)
    }
}

/** Issue-time validation of a draft against the catalogue in force. Empty list = issuable. */
object DraftValidator {
    /** Provisional ceiling per invoice (BBD 9,999,999.99); confirm with the client. */
    const val MAX_INVOICE_TOTAL_MINOR = 999_999_999L

    fun problems(draft: InvoiceDraft, products: Map<ProductId, Product>, priceOverrideAllowed: Boolean): List<DraftProblem> {
        val out = mutableListOf<DraftProblem>()
        if (draft.customerId == null) out += DraftProblem(DraftProblemCode.NO_CUSTOMER)
        if (draft.lines.isEmpty()) out += DraftProblem(DraftProblemCode.NO_LINES)
        for (line in draft.lines) {
            fun add(code: DraftProblemCode) { out += DraftProblem(code, line.id) }
            if (line.description.isBlank()) add(DraftProblemCode.MISSING_DESCRIPTION)
            if (line.quantity == null) add(DraftProblemCode.MISSING_QUANTITY)
            if (line.unitPrice == null) add(DraftProblemCode.MISSING_PRICE)
            if (line.unitPrice?.isZero == true && !line.zeroPriceConfirmed) add(DraftProblemCode.ZERO_PRICE_NOT_CONFIRMED)
            if (line.productId != null) {
                val product = products[line.productId]
                when {
                    product == null -> add(DraftProblemCode.PRODUCT_NOT_IN_CATALOGUE)
                    !product.active -> add(DraftProblemCode.PRODUCT_INACTIVE)
                    !product.allows(line.unit) -> add(DraftProblemCode.UNIT_NOT_ALLOWED)
                }
            }
            if (line.overridesCataloguePrice && !priceOverrideAllowed) add(DraftProblemCode.PRICE_OVERRIDE_NOT_AUTHORIZED)
        }
        val total = runCatching { draft.runningTotal.minor }.getOrDefault(Long.MAX_VALUE)
        if (total > MAX_INVOICE_TOTAL_MINOR) out += DraftProblem(DraftProblemCode.TOTAL_TOO_LARGE)
        return out
    }
}
