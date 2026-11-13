package bb.produce.invoicing.domain.stock

import bb.produce.invoicing.domain.Fx
import bb.produce.invoicing.domain.catalogue.BaseQuantity
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.catalogue.StockConversion
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class StockAndShiftTest {

    private val van = LocationId(Fx.uuid(50))
    private val shift = ShiftId(Fx.uuid(60))
    private val limes = Fx.limes.id

    private fun mv(
        kind: StockMovementKind,
        qty: String,
        bucket: StockBucket = StockBucket.SALEABLE,
        by: Actor = Fx.owner,
        product: ProductId = limes,
        reason: String? = null,
        reverses: EventId? = null,
        id: EventId = EventId.random(),
    ) = StockMovement(id, shift, van, product, kind, bucket, BaseQuantity(BigDecimal(qty), Fx.each), by, Fx.T0,
        saleReference = if (kind == StockMovementKind.SALE_DEBIT) bb.produce.invoicing.domain.stock.SaleReference(
            bb.produce.invoicing.domain.common.InvoiceId(Fx.uuid(70)), bb.produce.invoicing.domain.common.LineId(EventId.random().value),
        ) else null,
        reversesMovementId = reverses, reason = reason)

    private fun StockLedger.add(m: StockMovement, p: NegativeStockPolicy = NegativeStockPolicy.BLOCK) = append(m, p).getOrThrow()

    @Test
    fun `S1 - load 20, sell 5, return 2 into quarantine, spoil 1 gives saleable 14 and quarantined 2`() {
        val l = StockLedger.empty(van)
            .add(mv(StockMovementKind.OPENING_LOAD, "20"))
            .add(mv(StockMovementKind.SALE_DEBIT, "5", by = Fx.actorAb))
            .add(mv(StockMovementKind.CUSTOMER_RETURN_IN, "2", StockBucket.QUARANTINE, by = Fx.actorAb))
            .add(mv(StockMovementKind.SPOILAGE, "1", by = Fx.actorAb, reason = "Bruised"))
        val b = l.balance(limes)
        assertEquals(0, BigDecimal("14").compareTo(b.saleable))
        assertEquals(0, BigDecimal("2").compareTo(b.quarantined))
        // Count of 13 -> variance -1 (signed).
        assertEquals(0, BigDecimal("-1").compareTo(b.variance(BigDecimal("13"))))
    }

    @Test
    fun `owner release moves quarantine to saleable and drivers cannot release or adjust`() {
        var l = StockLedger.empty(van)
            .add(mv(StockMovementKind.OPENING_LOAD, "10"))
            .add(mv(StockMovementKind.CUSTOMER_RETURN_IN, "2", StockBucket.QUARANTINE, by = Fx.actorAb))
        assertEquals(
            Outcome.Err(StockError.NOT_AUTHORIZED),
            l.append(mv(StockMovementKind.QUARANTINE_RELEASE, "2", StockBucket.QUARANTINE, by = Fx.actorAb), NegativeStockPolicy.BLOCK),
        )
        assertEquals(
            Outcome.Err(StockError.NOT_AUTHORIZED),
            l.append(mv(StockMovementKind.COUNT_ADJUSTMENT, "-1", by = Fx.actorAb, reason = "count"), NegativeStockPolicy.BLOCK),
        )
        assertEquals(
            Outcome.Err(StockError.NOT_AUTHORIZED),
            l.append(mv(StockMovementKind.TOP_UP, "5", by = Fx.actorAb), NegativeStockPolicy.BLOCK),
        )
        l = l.add(mv(StockMovementKind.QUARANTINE_RELEASE, "2", StockBucket.QUARANTINE))
        assertEquals(0, BigDecimal("12").compareTo(l.balance(limes).saleable))
        assertEquals(0, BigDecimal.ZERO.compareTo(l.balance(limes).quarantined))
    }

    @Test
    fun `S2 - retrying the same SALE_DEBIT 10 times leaves one movement`() {
        var l = StockLedger.empty(van).add(mv(StockMovementKind.OPENING_LOAD, "20"))
        val sale = mv(StockMovementKind.SALE_DEBIT, "5", by = Fx.actorAb)
        repeat(10) { l = l.add(sale) }
        assertEquals(2, l.movements.size)
        assertEquals(0, BigDecimal("15").compareTo(l.balance(limes).saleable))
        // A new event id for the same invoice line is a duplicate debit, not a second sale.
        assertEquals(Outcome.Err(StockError.DUPLICATE_SALE_DEBIT), l.append(sale.copy(id = EventId.random()), NegativeStockPolicy.BLOCK))
        assertEquals(Outcome.Err(StockError.EVENT_ID_CONFLICT), l.append(sale.copy(reason = "changed"), NegativeStockPolicy.BLOCK))
    }

    @Test
    fun `negative stock policy is explicit`() {
        val l = StockLedger.empty(van).add(mv(StockMovementKind.OPENING_LOAD, "2"))
        val oversell = mv(StockMovementKind.SALE_DEBIT, "3", by = Fx.actorAb)
        assertEquals(Outcome.Err(StockError.INSUFFICIENT_STOCK), l.append(oversell, NegativeStockPolicy.BLOCK))
        val allowed = l.append(oversell, NegativeStockPolicy.ALLOW_AND_FLAG).getOrThrow()
        assertEquals(0, BigDecimal("-1").compareTo(allowed.balance(limes).saleable))
    }

    @Test
    fun `reversal negates its target once and needs a reason`() {
        val load = mv(StockMovementKind.TOP_UP, "5")
        var l = StockLedger.empty(van).add(mv(StockMovementKind.OPENING_LOAD, "10")).add(load)
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.REVERSAL, "0", reverses = load.id) }
        val rev = mv(StockMovementKind.REVERSAL, "0", reverses = load.id, reason = "Top-up entered twice")
        l = l.add(rev)
        assertEquals(0, BigDecimal("10").compareTo(l.balance(limes).saleable))
        assertEquals(Outcome.Err(StockError.ALREADY_REVERSED),
            l.append(rev.copy(id = EventId.random()), NegativeStockPolicy.BLOCK))
        assertEquals(Outcome.Err(StockError.CANNOT_REVERSE_REVERSAL),
            l.append(mv(StockMovementKind.REVERSAL, "0", reverses = rev.id, reason = "x"), NegativeStockPolicy.BLOCK))
    }

    @Test
    fun `movements validate kind, bucket, sign and required reasons`() {
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.SALE_DEBIT, "-1", by = Fx.actorAb) }
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.OPENING_LOAD, "1", bucket = StockBucket.QUARANTINE) }
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.CUSTOMER_RETURN_IN, "1") } // must be quarantine
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.SPOILAGE, "1") } // reason
        assertThrows<IllegalArgumentException> { mv(StockMovementKind.COUNT_ADJUSTMENT, "0", reason = "x") }
    }

    @Test
    fun `sale debits convert sell units to base units with the versioned mapping`() {
        // 2 cases of limes (1 case = 12 each) + 1.5 kg tomatoes (base lb, factor 2.20462).
        var d = Fx.validDraft()
        d = d.updateQuantity(d.lines[1].id, null, Fx.T0).getOrThrow().removeLine(d.lines[1].id, Fx.T0).getOrThrow()
        d = d.addProduct(Fx.limes, Fx.case, Fx.T0).getOrThrow()
        d = d.updateQuantity(d.lines[1].id, Quantity.of("2"), Fx.T0).getOrThrow()
        d = d.changeUnit(d.lines[0].id, Fx.kg, Fx.tomatoes, Fx.T0).getOrThrow()
        d = d.updateQuantity(d.lines[0].id, Quantity.of("1.5"), Fx.T0).getOrThrow()
        d = d.addCustomLine("Crate deposit", Fx.each, Fx.T0)
        d = d.updateUnitPrice(d.lines[2].id, bb.produce.invoicing.domain.money.UnitPrice.ofMinor(500), Fx.T0).getOrThrow()
        val inv = Fx.issue(draft = d).invoice

        val table = ConversionTable(listOf(
            StockConversion(Fx.limes.id, Fx.case, Fx.each, BigDecimal("12"), version = 3),
            StockConversion(Fx.tomatoes.id, Fx.kg, Fx.lb, BigDecimal("2.20462"), version = 1),
        ))
        val debits = SaleDebits.forInvoice(inv, table, shift, van, Fx.actorAb).getOrThrow()
        assertEquals(2, debits.size) // custom line is not stock-tracked
        assertEquals(BaseQuantity(BigDecimal("3.30693"), Fx.lb), debits[0].quantity)
        assertEquals(BaseQuantity(BigDecimal("24"), Fx.each), debits[1].quantity)
        assertEquals(3, debits[1].conversionVersion)
        assertTrue(debits.all { it.saleReference?.invoiceId == inv.id })
        assertEquals(inv.lines.take(2).map { it.lineId }, debits.map { it.saleReference?.lineId })

        // Missing conversion is an error, never a 1:1 guess.
        val missing = SaleDebits.forInvoice(inv, ConversionTable(table.let { emptyList() }), shift, van, Fx.actorAb)
        assertTrue(missing is Outcome.Err && missing.error is SaleDebitError.MissingConversion)
    }

    @Test
    fun `conversion table refuses duplicate mappings and conversions refuse bad factors`() {
        val c = StockConversion(limes, Fx.case, Fx.each, BigDecimal("12"), 1)
        assertThrows<IllegalArgumentException> { ConversionTable(listOf(c, c.copy(version = 2))) }
        assertThrows<IllegalArgumentException> { c.copy(factor = BigDecimal.ZERO) }
        assertThrows<IllegalArgumentException> { c.copy(factor = BigDecimal("0.0000001")) }
    }

    @Test
    fun `ledger refuses mixed units for the same product`() {
        val l = StockLedger.empty(van).add(mv(StockMovementKind.OPENING_LOAD, "20"))
        val lbMove = mv(StockMovementKind.TOP_UP, "1").copy(quantity = BaseQuantity(BigDecimal.ONE, UnitCode.of("lb")))
        assertEquals(Outcome.Err(StockError.UNIT_MISMATCH), l.append(lbMove, NegativeStockPolicy.BLOCK))
    }

    // ---------------- shift lifecycle ----------------

    private val ready = CloseReadiness(0, 0, 0)

    @Test
    fun `shift happy path and permissions`() {
        fun t(from: ShiftStatus, to: ShiftStatus, a: Actor, r: CloseReadiness? = null, reason: String? = null) =
            ShiftLifecycle.transition(from, to, a, reason, r)
        assertEquals(Outcome.Err(ShiftError.NOT_AUTHORIZED), t(ShiftStatus.PLANNED, ShiftStatus.LOADED, Fx.actorAb))
        assertEquals(Outcome.Ok(ShiftStatus.LOADED), t(ShiftStatus.PLANNED, ShiftStatus.LOADED, Fx.owner))
        assertEquals(Outcome.Ok(ShiftStatus.OPEN), t(ShiftStatus.LOADED, ShiftStatus.OPEN, Fx.actorAb))
        assertEquals(Outcome.Ok(ShiftStatus.CLOSING_PENDING_SYNC), t(ShiftStatus.OPEN, ShiftStatus.CLOSING_PENDING_SYNC, Fx.actorAb))
        assertEquals(Outcome.Ok(ShiftStatus.RECONCILED), t(ShiftStatus.CLOSING_PENDING_SYNC, ShiftStatus.RECONCILED, Fx.owner, ready))
        assertEquals(Outcome.Err(ShiftError.NOT_AUTHORIZED), t(ShiftStatus.RECONCILED, ShiftStatus.APPROVED, Fx.actorAb, ready))
        assertEquals(Outcome.Ok(ShiftStatus.APPROVED), t(ShiftStatus.RECONCILED, ShiftStatus.APPROVED, Fx.owner, ready))
        assertEquals(Outcome.Err(ShiftError.INVALID_TRANSITION), t(ShiftStatus.OPEN, ShiftStatus.APPROVED, Fx.owner, ready))
    }

    @Test
    fun `S5 - owner cannot approve with unsynced events, conflicts or unexplained variance`() {
        val t = { r: CloseReadiness? -> ShiftLifecycle.transition(ShiftStatus.RECONCILED, ShiftStatus.APPROVED, Fx.owner, null, r) }
        assertEquals(Outcome.Err(ShiftError.UNSYNCED_EVENTS_REMAIN), t(null))
        assertEquals(Outcome.Err(ShiftError.UNSYNCED_EVENTS_REMAIN), t(CloseReadiness(1, 0, 0)))
        assertEquals(Outcome.Err(ShiftError.UNSYNCED_EVENTS_REMAIN), t(CloseReadiness(0, 1, 0)))
        assertEquals(Outcome.Err(ShiftError.UNRESOLVED_VARIANCE), t(CloseReadiness(0, 0, 1)))
    }

    @Test
    fun `reopening needs a reason`() {
        assertEquals(Outcome.Err(ShiftError.REASON_REQUIRED),
            ShiftLifecycle.transition(ShiftStatus.APPROVED, ShiftStatus.REOPENED_WITH_REASON, Fx.owner, " "))
        assertEquals(Outcome.Ok(ShiftStatus.REOPENED_WITH_REASON),
            ShiftLifecycle.transition(ShiftStatus.APPROVED, ShiftStatus.REOPENED_WITH_REASON, Fx.owner, "Late invoice arrived"))
    }
}
