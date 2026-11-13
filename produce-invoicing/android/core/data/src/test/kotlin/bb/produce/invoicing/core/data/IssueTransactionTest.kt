package bb.produce.invoicing.core.data

import bb.produce.invoicing.domain.audit.OutboxEventType
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.identity.LeaseError
import bb.produce.invoicing.domain.invoice.DraftProblemCode
import bb.produce.invoicing.domain.invoice.IssueError
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.payment.PaymentMethod
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.domain.stock.StockError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IssueTransactionTest : DbTestBase() {

    override val dbName = "issue-test.db"

    private fun issued(r: IssueResult) = (r as? IssueResult.Issued) ?: error("Expected Issued but was $r")

    @Test
    fun issueWritesInvoiceLinesLeaseStockAuditAndOutboxTogether() = runBlocking {
        val draft = savedDraft()
        val result = issued(repo.issue(draft.id, driver))
        val inv = result.invoice

        assertEquals("DEMO-100-AB", inv.number.text)
        assertEquals(Money.ofMinor(10_425), inv.total)
        assertEquals(inv, repo.invoice(inv.id)) // round-trips through the database exactly
        assertEquals(SyncState.PENDING, repo.syncState(inv.id))
        assertNull(repo.invoice(inv.id)!!.registerReference)

        assertEquals(1, count("invoice"))
        assertEquals(2, count("invoice_line"))
        assertEquals("101", scalar("SELECT next_sequence FROM driver_lease"))
        assertEquals(2, db.stockDao().saleDebitsFor(inv.id.toString()).size)
        assertEquals(0, BigDecimal("187.5").compareTo(saleableOf(0)))  // 200 - 12.5 lb
        assertEquals(0, BigDecimal("576").compareTo(saleableOf(1)))    // 600 - 2 case x 12
        assertEquals(1, db.auditDao().forEntity(inv.id.toString()).size)
        val outbox = repo.pendingOutbox()
        assertEquals(listOf("INVOICE_ISSUED", "STOCK_MOVEMENT", "STOCK_MOVEMENT"), outbox.map { it.type })
        assertEquals(inv.id.toString(), outbox.first().aggregateId)
        assertNull(repo.loadDraft(draft.id)) // draft consumed by the issue
    }

    @Test
    fun everyFailurePointRollsBackEverything() = runBlocking {
        for (step in IssueStep.entries) {
            val draft = savedDraft()
            val failing = InvoicingRepository(db, { clockNow }) { if (it == step) throw IllegalStateException("injected at $step") }
            try {
                failing.issue(draft.id, driver)
                fail("expected injected failure at $step")
            } catch (e: IllegalStateException) {
                assertEquals("injected at $step", e.message)
            }
            assertEquals("invoice rows after $step", 0, count("invoice"))
            assertEquals("lines after $step", 0, count("invoice_line"))
            assertEquals("lease after $step", "100", scalar("SELECT next_sequence FROM driver_lease"))
            assertEquals("stock after $step", 4, count("stock_movement")) // opening loads only
            assertEquals("audit after $step", 0, count("audit_event"))
            assertEquals("outbox after $step", 0, count("outbox_event"))
            assertNotNull("draft after $step", repo.loadDraft(draft.id))
            assertTrue(repo.discardDraft(draft.id))
        }
        // The uncommitted number was never issued, so the next real issue still gets 100.
        assertEquals("DEMO-100-AB", issued(repo.issue(savedDraft().id, driver)).invoice.number.text)
    }

    @Test
    fun n18DoubleTapIssuesExactlyOneInvoice() = runBlocking {
        val draft = savedDraft()
        val results = (1..8).map { async(Dispatchers.IO) { repo.issue(draft.id, driver) } }.awaitAll()
        val issued = results.filterIsInstance<IssueResult.Issued>()
        val already = results.filterIsInstance<IssueResult.AlreadyIssued>()
        assertEquals(1, issued.size)
        assertEquals(7, already.size)
        assertTrue(already.all { it.invoice.id == issued.single().invoice.id })
        assertEquals(1, count("invoice"))
        assertEquals(2, count("stock_movement") - 4)
        assertEquals("101", scalar("SELECT next_sequence FROM driver_lease"))
    }

    @Test
    fun n07N08DraftsAndIssuedInvoicesSurviveRestartAndTheSequenceContinues() = runBlocking {
        val first = issued(repo.issue(savedDraft().id, driver)).invoice
        var pending = savedDraft()
        pending = pending.updateQuantity(pending.lines[0].id, Quantity.of("0.335"), t0).getOrThrow()
        repo.saveDraft(pending).getOrThrow()

        restart()

        assertEquals(first, repo.invoice(first.id))
        assertEquals(pending, repo.loadDraft(pending.id))
        assertEquals(3, repo.pendingOutbox().size)
        clockNow = t0.plusSeconds(86_400) // next business day
        val second = issued(repo.issue(pending.id, driver)).invoice
        assertEquals("DEMO-101-AB", second.number.text)

        restart()
        assertEquals(second, repo.invoice(second.id))
        assertEquals("102", scalar("SELECT next_sequence FROM driver_lease"))
    }

    @Test
    fun rejectionsWriteNothing() = runBlocking {
        fun assertNothingWritten(label: String) {
            assertEquals(label, 0, count("invoice"))
            assertEquals(label, 0, count("audit_event"))
            assertEquals(label, 0, count("outbox_event"))
            assertEquals(label, 4, count("stock_movement"))
            assertEquals(label, "100", scalar("SELECT next_sequence FROM driver_lease"))
        }

        // No customer.
        val noCustomer = savedDraft().withCustomer(null, t0).also { repo.saveDraft(it).getOrThrow() }
        val r1 = repo.issue(noCustomer.id, driver) as IssueResult.Rejected
        val problems = ((r1.reason as IssueRejection.Domain).error as IssueError.Invalid).problems.map { it.code }
        assertTrue(DraftProblemCode.NO_CUSTOMER in problems)
        assertNothingWritten("no customer")

        // Not enough stock on the van (BLOCK policy): 300 lb > 200 loaded.
        var big = savedDraft()
        big = big.updateQuantity(big.lines[0].id, Quantity.of("300"), t0).getOrThrow().also { repo.saveDraft(it).getOrThrow() }
        assertEquals(IssueResult.Rejected(IssueRejection.Stock(StockError.INSUFFICIENT_STOCK)), repo.issue(big.id, driver))
        assertNothingWritten("insufficient stock")

        // Lease revoked.
        db.leaseDao().revoke(DemoData.businessId.toString(), "AB", 0)
        assertEquals(
            IssueResult.Rejected(IssueRejection.Domain(IssueError.Lease(LeaseError.REVOKED))),
            repo.issue(savedDraft().id, driver),
        )
        assertEquals("100", scalar("SELECT next_sequence FROM driver_lease"))
        assertEquals(0, count("invoice"))
    }

    @Test
    fun issuingNeedsAnOpenShiftAndAKnownConversion() = runBlocking {
        db.stockDao().setShiftStatus(DemoData.shift.toString(), "CLOSING_PENDING_SYNC", null, 0)
        assertEquals(IssueResult.Rejected(IssueRejection.NoOpenShift), repo.issue(savedDraft().id, driver))
        db.stockDao().setShiftStatus(DemoData.shift.toString(), "OPEN", null, 0)

        // Thyme sold in a unit with no conversion record -> rejected, never guessed 1:1.
        sql("DELETE FROM stock_conversion WHERE product_id = '${DemoData.products[3].id}'")
        var d = savedDraft()
        d = d.addProduct(DemoData.products[3], bb.produce.invoicing.domain.money.UnitCode.of("bunch"), t0).getOrThrow()
        repo.saveDraft(d).getOrThrow()
        val r = repo.issue(d.id, driver) as IssueResult.Rejected
        assertTrue(r.reason is IssueRejection.MissingConversion)
        assertEquals(0, count("invoice"))
    }

    @Test
    fun pendingPaymentAndStockEventsSurviveRestartAndReplayIsIdempotent() = runBlocking {
        val inv = issued(repo.issue(savedDraft().id, driver)).invoice
        val ledger = repo.paymentLedger(inv.id)!!
        val paid = ledger.markPaid(driver, PaymentMethod.CASH, inv.total, t0, setOf(PaymentMethod.CASH)).getOrThrow()
        repo.recordPayment(paid, driver).getOrThrow()

        restart()

        val reloaded = repo.paymentLedger(inv.id)!!
        assertEquals(PaymentStatus.PAID, reloaded.status)
        assertEquals(PaymentMethod.CASH, reloaded.paidMethod)
        repeat(10) { repo.recordPayment(paid, driver).getOrThrow() } // replay: no new rows
        assertEquals(1, count("payment_event"))
        val types = repo.pendingOutbox().map { it.type }
        assertEquals(listOf("INVOICE_ISSUED", "STOCK_MOVEMENT", "STOCK_MOVEMENT", "PAYMENT_EVENT"), types)
        assertEquals(inv.total, repo.invoice(inv.id)!!.total) // payment never changes the invoice

        val reversal = reloaded.reversePaid(owner, "Entered on wrong invoice", t0).getOrThrow()
        assertEquals(PaymentStatus.UNPAID, repo.recordPayment(reversal, owner).getOrThrow().status)
        assertEquals(2, count("payment_event"))
    }

    @Test
    fun outboxAcknowledgementIsIdempotent() = runBlocking {
        issued(repo.issue(savedDraft().id, driver))
        val first = repo.pendingOutbox().first()
        val id = EventId(java.util.UUID.fromString(first.eventId))
        assertTrue(repo.acknowledgeOutbox(id))
        clockNow = t0.plusSeconds(60)
        assertEquals(false, repo.acknowledgeOutbox(id))
        assertEquals(t0.toEpochMilli().toString(), scalar("SELECT acked_at FROM outbox_event WHERE event_id = '${first.eventId}'"))
        assertEquals(2, repo.pendingOutbox().size)
        assertEquals(OutboxEventType.STOCK_MOVEMENT.name, repo.pendingOutbox().first().type)
    }

    @Test
    fun explicitInvoiceIdIsUsedAndUnknownDraftIsRejected() = runBlocking {
        val id = InvoiceId.random()
        assertEquals(id, issued(repo.issue(savedDraft().id, driver, id)).invoice.id)
        assertEquals(IssueResult.Rejected(IssueRejection.DraftNotFound), repo.issue(bb.produce.invoicing.domain.invoice.DraftId.random(), driver))
        assertTrue(Outcome.Err(InvoicingRepository.DraftSaveError.ALREADY_ISSUED) == repo.saveDraft(repo.invoice(id)!!.let { savedDraft().copy(id = it.sourceDraftId) }))
    }
}
