package bb.produce.invoicing.domain.payment

import bb.produce.invoicing.domain.Fx
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PaymentsTest {

    private val all = PaymentMethod.entries.toSet() - PaymentMethod.NONE
    private val inv = Fx.issue().invoice // total BBD 57.00

    private fun paidLedger(): Pair<PaymentLedger, PaymentEvent> {
        val ledger = PaymentLedger.forInvoice(inv)
        val ev = ledger.markPaid(Fx.actorAb, PaymentMethod.CASH, inv.total, Fx.T0, all).getOrThrow()
        return ledger.record(ev).getOrThrow() to ev
    }

    @Test
    fun `new invoices are UNPAID by default with the full amount outstanding`() {
        val l = PaymentLedger.forInvoice(inv)
        assertEquals(PaymentStatus.UNPAID, l.status)
        assertEquals(inv.total, l.outstanding)
        assertNull(l.paidMethod)
    }

    @Test
    fun `P1 - marking paid with cash raises paid total but not sales total`() {
        val before = PaymentSummary.of(listOf(inv to PaymentLedger.forInvoice(inv)))
        val (paid, _) = paidLedger()
        val after = PaymentSummary.of(listOf(inv to paid))
        assertEquals(PaymentStatus.PAID, paid.status)
        assertEquals(before.issuedGross, after.issuedGross) // sales unchanged
        assertEquals(Money.zero(), before.paidGross)
        assertEquals(inv.total, after.paidGross)
        assertEquals(mapOf(PaymentMethod.CASH to inv.total), after.paidGrossByMethod)
        assertEquals(Money.zero(), after.unpaidGross)
        assertEquals(inv.total, before.unpaidGross)
    }

    @Test
    fun `P2 - owner reversal restores UNPAID and keeps both events`() {
        val (paid, paidEvent) = paidLedger()
        assertEquals(Outcome.Err(PaymentError.NOT_AUTHORIZED), paid.reversePaid(Fx.actorAb, "mistake", Fx.T0))
        assertEquals(Outcome.Err(PaymentError.REASON_REQUIRED), paid.reversePaid(Fx.owner, " ", Fx.T0))
        val rev = paid.reversePaid(Fx.owner, "Customer had not paid yet", Fx.T0).getOrThrow()
        val reversed = paid.record(rev).getOrThrow()
        assertEquals(PaymentStatus.UNPAID, reversed.status)
        assertEquals(listOf(paidEvent.id, rev.id), reversed.events.map { it.id })
        assertEquals(paidEvent.id, rev.reversesEventId)
        assertEquals(Money.zero(), PaymentSummary.of(listOf(inv to reversed)).paidGross)
        // Can then be marked paid again with the correct method.
        val again = reversed.markPaid(Fx.actorAb, PaymentMethod.BANK_TRANSFER, inv.total, Fx.T0, all).getOrThrow()
        assertEquals(PaymentMethod.BANK_TRANSFER, reversed.record(again).getOrThrow().paidMethod)
    }

    @Test
    fun `P3 - partial, over and zero payments are blocked`() {
        val l = PaymentLedger.forInvoice(inv)
        assertEquals(Outcome.Err(PaymentError.AMOUNT_MUST_EQUAL_OUTSTANDING), l.markPaid(Fx.actorAb, PaymentMethod.CASH, inv.total - Money.ofMinor(1), Fx.T0, all))
        assertEquals(Outcome.Err(PaymentError.AMOUNT_MUST_EQUAL_OUTSTANDING), l.markPaid(Fx.actorAb, PaymentMethod.CASH, inv.total + Money.ofMinor(1), Fx.T0, all))
        assertThrows<IllegalArgumentException> {
            PaymentEvent(EventId.random(), inv.id, PaymentEventKind.MARKED_PAID, PaymentMethod.CASH, Money.zero(), Fx.driverAbUser, Fx.deviceAb, Fx.T0)
        }
    }

    @Test
    fun `method must be chosen, enabled, and not NONE`() {
        val l = PaymentLedger.forInvoice(inv)
        assertEquals(Outcome.Err(PaymentError.METHOD_REQUIRED), l.markPaid(Fx.actorAb, PaymentMethod.NONE, inv.total, Fx.T0, all))
        assertEquals(
            Outcome.Err(PaymentError.METHOD_NOT_ENABLED),
            l.markPaid(Fx.actorAb, PaymentMethod.CHEQUE, inv.total, Fx.T0, setOf(PaymentMethod.CASH)),
        )
    }

    @Test
    fun `cannot mark paid twice, after void, or on another driver's invoice`() {
        val (paid, _) = paidLedger()
        assertEquals(Outcome.Err(PaymentError.ALREADY_PAID), paid.markPaid(Fx.actorAb, PaymentMethod.CASH, inv.total, Fx.T0, all))

        val voided = inv.void(Fx.owner, "test void", Fx.T0).getOrThrow()
        assertEquals(
            Outcome.Err(PaymentError.INVOICE_VOIDED),
            PaymentLedger.forInvoice(voided).markPaid(Fx.owner, PaymentMethod.CASH, inv.total, Fx.T0, all),
        )
        assertEquals(
            Outcome.Err(PaymentError.NOT_AUTHORIZED),
            PaymentLedger.forInvoice(inv).markPaid(Fx.actorFd, PaymentMethod.CASH, inv.total, Fx.T0, all),
        )
        assertTrue(PaymentLedger.forInvoice(inv).markPaid(Fx.owner, PaymentMethod.CASH, inv.total, Fx.T0, all).isOk)
    }

    @Test
    fun `replaying the same paid event 10 times yields one settlement`() {
        val (paid, ev) = paidLedger()
        var l = paid
        repeat(10) { l = l.record(ev).getOrThrow() }
        assertEquals(1, l.events.size)
        // Same id with different content is a conflict, not an overwrite.
        assertEquals(Outcome.Err(PaymentError.EVENT_ID_CONFLICT), l.record(ev.copy(method = PaymentMethod.OTHER)))
        // A second, different PAID event is refused.
        assertEquals(Outcome.Err(PaymentError.ALREADY_PAID), l.record(ev.copy(id = EventId.random())))
    }

    @Test
    fun `voided invoices are reported separately and not as unpaid sales`() {
        val other = Fx.issue(lease = Fx.lease(Fx.ab, Fx.deviceAb, next = 101)).invoice
        val voided = other.void(Fx.owner, "duplicate", Fx.T0).getOrThrow()
        val s = PaymentSummary.of(listOf(inv to PaymentLedger.forInvoice(inv), voided to PaymentLedger.forInvoice(voided)))
        assertEquals(1, s.invoiceCount)
        assertEquals(inv.total, s.issuedGross)
        assertEquals(voided.total, s.voidedGross)
        assertEquals(inv.total, s.unpaidGross)
    }

    @ParameterizedTest
    @ValueSource(strings = ["4111 1111 1111 1111", "4111-1111-1111-1111", "ref 4111111111111111", "5555555555554444"])
    fun `external references that look like card numbers are rejected`(ref: String) {
        assertFalse(ExternalReference.isAcceptable(ref))
        assertEquals(
            Outcome.Err(PaymentError.EXTERNAL_REFERENCE_REJECTED),
            PaymentLedger.forInvoice(inv).markPaid(Fx.actorAb, PaymentMethod.CARD_EXTERNAL, inv.total, Fx.T0, all, ref),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["CHQ 004512", "TRF-2026-11-13-0042", "1234567890123", "Receipt 88",
        "12345678901234567890123"]) // >19 digits is not a card number, even if a sub-window passes Luhn
    fun `ordinary references are accepted`(ref: String) {
        assertTrue(ExternalReference.isAcceptable(ref), ref)
    }
}
