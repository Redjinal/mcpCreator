package bb.produce.invoicing.core.data

import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.identity.RegisterReference
import bb.produce.invoicing.domain.identity.RegisterSequence
import bb.produce.invoicing.domain.invoice.InvoiceStatus
import bb.produce.invoicing.domain.invoice.RegistrationError
import bb.produce.invoicing.domain.invoice.ServerRegistration
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.invoice.VoidError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegistrationVoidTest : DbTestBase() {

    override val dbName = "registration-test.db"

    private fun reg(invoiceId: InvoiceId, number: bb.produce.invoicing.domain.identity.CustomerInvoiceNumber, n: Long) = ServerRegistration(
        invoiceId, number, RegisterReference(BusinessCode.of("DEMO"), RegisterSequence(n)), t0.plusSeconds(3600), t0.plusSeconds(3601),
    )

    @Test
    fun registrationIsAppliedOnceAndSurvivesRestart() = runBlocking {
        val inv = (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice
        val ack = reg(inv.id, inv.number, 1)
        val registered = repo.recordRegistration(ack, driver).getOrThrow()
        assertEquals("DEMO-1", registered.registerReference?.text)
        assertEquals("DEMO-100-AB", registered.number.text)
        assertEquals(SyncState.SYNCED, repo.syncState(inv.id))

        // Identical ACK replay: no change, no extra audit.
        repo.recordRegistration(ack, driver).getOrThrow()
        assertEquals(2, db.auditDao().forEntity(inv.id.toString()).size) // ISSUED + REGISTERED

        // A different reference for the same invoice is refused and nothing changes.
        assertEquals(
            Outcome.Err(InvoicingRepository.RegistrationRejection.Domain(RegistrationError.CONFLICTING_REGISTER_REFERENCE)),
            repo.recordRegistration(reg(inv.id, inv.number, 2), driver),
        )
        assertEquals(
            Outcome.Err(InvoicingRepository.RegistrationRejection.UnknownInvoice),
            repo.recordRegistration(reg(InvoiceId.random(), inv.number, 3), driver),
        )

        restart()
        val reloaded = repo.invoice(inv.id)!!
        assertEquals("DEMO-1", reloaded.registerReference?.text)
        assertEquals(t0.plusSeconds(3600), reloaded.firstReceivedAtServer)
        assertEquals(t0.plusSeconds(3601), reloaded.registeredAtServer)
        assertEquals(t0, reloaded.issuedAtDevice)
    }

    @Test
    fun voidIsOwnerOnlyAuditedQueuedAndKeepsTheNumber() = runBlocking {
        val inv = (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice
        assertEquals(Outcome.Err(VoidError.NOT_AUTHORIZED), repo.void(inv.id, driver, "mistake"))
        val voided = repo.void(inv.id, owner, "Wrong customer").getOrThrow()
        assertEquals(InvoiceStatus.VOIDED, voided.status)
        assertEquals(Outcome.Err(VoidError.ALREADY_VOIDED), repo.void(inv.id, owner, "again"))

        restart()
        val reloaded = repo.invoice(inv.id)!!
        assertEquals(InvoiceStatus.VOIDED, reloaded.status)
        assertEquals(inv.number, reloaded.number)
        assertEquals(inv.total, reloaded.total)
        assertEquals(listOf("INVOICE_ISSUED", "INVOICE_VOIDED"), db.auditDao().forEntity(inv.id.toString()).map { it.action })
        assertEquals(1, repo.pendingOutbox().count { it.type == "INVOICE_VOIDED" })
        // N13: the voided number is not recycled.
        assertEquals("DEMO-101-AB", (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice.number.text)
    }

    @Test
    fun n09DriverRenameKeepsOldSnapshotOnEarlierInvoices() = runBlocking {
        val first = (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice
        db.leaseDao().rename(DemoData.businessId.toString(), "AB", "Renamed Driver (synthetic)", 0)
        val second = (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice
        assertEquals("Demo Driver (synthetic)", repo.invoice(first.id)!!.driver.displayName)
        assertEquals("Renamed Driver (synthetic)", second.driver.displayName)
        assertNull(repo.invoice(first.id)!!.voidRecord)
    }
}
