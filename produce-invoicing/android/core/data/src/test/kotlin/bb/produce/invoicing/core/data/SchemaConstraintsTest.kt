package bb.produce.invoicing.core.data

import bb.produce.invoicing.core.data.db.ShiftEntity
import bb.produce.invoicing.core.data.db.StockMovementEntity
import bb.produce.invoicing.domain.common.InvoiceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Database-level guarantees: even raw SQL cannot rewrite issued history or reuse identities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchemaConstraintsTest : DbTestBase() {

    private fun issueOne() = runBlocking { (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice }

    @Test
    fun issuedInvoiceSnapshotCannotBeEditedOrDeleted() {
        val inv = issueOne()
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET total_minor = 1 WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET customer_invoice_number = 'DEMO-999-AB' WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET cust_name = 'Someone else' WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET issued_at_device = 0 WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("DELETE FROM invoice WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice_line SET quantity = '1.000' WHERE invoice_id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("DELETE FROM invoice_line WHERE invoice_id = '${inv.id}'") }
        // Sync state is operational metadata and may change.
        sql("UPDATE invoice SET sync_state = 'SYNCING' WHERE id = '${inv.id}'")
        assertEquals(inv, runBlocking { repo.invoice(inv.id) })
    }

    @Test
    fun registerReferenceIsSetOnceWithMatchingPrefixAndVoidIsFinal() {
        val inv = issueOne()
        assertSqlFails("INVALID") {
            sql("UPDATE invoice SET register_business_code = 'XYZ', register_sequence = 1, first_received_at_server = 1, registered_at_server = 2 WHERE id = '${inv.id}'")
        }
        assertSqlFails("INVALID") { sql("UPDATE invoice SET register_business_code = 'DEMO', register_sequence = 1 WHERE id = '${inv.id}'") }
        sql("UPDATE invoice SET register_business_code = 'DEMO', register_sequence = 1, first_received_at_server = 1, registered_at_server = 2 WHERE id = '${inv.id}'")
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET register_sequence = 2 WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET register_sequence = NULL, register_business_code = NULL WHERE id = '${inv.id}'") }

        sql("UPDATE invoice SET void_reason = 'r', voided_by = 'u', void_device_id = 'd', voided_at = 5 WHERE id = '${inv.id}'")
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET voided_at = NULL WHERE id = '${inv.id}'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE invoice SET void_reason = 'other' WHERE id = '${inv.id}'") }
    }

    @Test
    fun customerNumberDriverSequenceAndRegisterReferenceAreUniquePerBusiness() = runBlocking {
        val inv = issueOne()
        val row = db.invoiceDao().get(inv.id.toString())!!
        // N06 locally: same customer-facing number with a different UUID cannot be stored.
        assertSqlFails("UNIQUE") { runBlocking { db.invoiceDao().insert(row.copy(id = UUID.randomUUID().toString(), sourceDraftId = UUID.randomUUID().toString())) } }
        // Same driver/sequence under a different text form.
        assertSqlFails("UNIQUE") {
            runBlocking {
                db.invoiceDao().insert(row.copy(id = UUID.randomUUID().toString(), sourceDraftId = UUID.randomUUID().toString(), customerInvoiceNumber = "DEMO-100-AB-X"))
            }
        }
        // One invoice per draft (N18 at the schema level).
        assertSqlFails("UNIQUE") {
            runBlocking { db.invoiceDao().insert(row.copy(id = UUID.randomUUID().toString(), driverSequence = 555, customerInvoiceNumber = "DEMO-555-AB")) }
        }
        // Register reference unique within the business; NULLs (unregistered) may repeat.
        val second = (repo.issue(savedDraft().id, driver) as IssueResult.Issued).invoice
        sql("UPDATE invoice SET register_business_code = 'DEMO', register_sequence = 7, first_received_at_server = 1, registered_at_server = 2 WHERE id = '${inv.id}'")
        assertSqlFails("UNIQUE") {
            sql("UPDATE invoice SET register_business_code = 'DEMO', register_sequence = 7, first_received_at_server = 1, registered_at_server = 2 WHERE id = '${second.id}'")
        }
    }

    @Test
    fun corruptedRowIsDetectedOnRead() = runBlocking {
        val inv = issueOne()
        val row = db.invoiceDao().get(inv.id.toString())!!
        val lines = db.invoiceDao().lines(inv.id.toString())
        val forgedId = UUID.randomUUID().toString()
        db.invoiceDao().insert(row.copy(id = forgedId, sourceDraftId = UUID.randomUUID().toString(), driverSequence = 900, customerInvoiceNumber = "DEMO-900-AB", totalMinor = 1, subtotalMinor = 1))
        db.invoiceDao().insertLines(lines.map { it.copy(id = UUID.randomUUID().toString(), invoiceId = forgedId) })
        try {
            repo.invoice(InvoiceId(UUID.fromString(forgedId)))
            throw AssertionError("forged totals were accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Stored totals do not match"))
        }
    }

    @Test
    fun driverLeaseCannotGoBackwardsChangeIdentityOrBeDeleted() {
        sql("UPDATE driver_lease SET next_sequence = 150")
        assertSqlFails("NUMBERING") { sql("UPDATE driver_lease SET next_sequence = 149") }
        assertSqlFails("NUMBERING") { sql("DELETE FROM driver_lease") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE driver_lease SET driver_user_id = 'someone-else'") }
        assertSqlFails("IMMUTABLE") { sql("UPDATE driver_lease SET driver_code = 'ZZ'") }
        sql("UPDATE driver_lease SET epoch = 2")
        assertSqlFails("FENCING") { sql("UPDATE driver_lease SET epoch = 1") }
    }

    @Test
    fun ledgersAndAuditAreAppendOnly() = runBlocking {
        val inv = issueOne()
        assertSqlFails("APPEND-ONLY") { sql("UPDATE stock_movement SET quantity = '0'") }
        assertSqlFails("APPEND-ONLY") { sql("DELETE FROM stock_movement") }
        assertSqlFails("APPEND-ONLY") { sql("UPDATE audit_event SET action = 'X'") }
        assertSqlFails("APPEND-ONLY") { sql("DELETE FROM audit_event") }

        // S2 at the schema level: a second SALE_DEBIT for the same invoice line is refused.
        val debit = db.stockDao().saleDebitsFor(inv.id.toString()).first()
        assertSqlFails("UNIQUE") { runBlocking { db.stockDao().insertMovements(listOf(debit.copy(id = UUID.randomUUID().toString()))) } }
        // The same event id cannot be inserted twice either.
        assertSqlFails("UNIQUE") { runBlocking { db.stockDao().insertMovements(listOf(debit)) } }

        val ledger = repo.paymentLedger(inv.id)!!
        val paid = ledger.markPaid(driver, bb.produce.invoicing.domain.payment.PaymentMethod.CASH, inv.total, t0,
            setOf(bb.produce.invoicing.domain.payment.PaymentMethod.CASH)).getOrThrow()
        repo.recordPayment(paid, driver).getOrThrow()
        assertSqlFails("APPEND-ONLY") { sql("UPDATE payment_event SET amount_minor = 1") }
        assertSqlFails("APPEND-ONLY") { sql("DELETE FROM payment_event") }
    }

    @Test
    fun onlyOneActiveShiftPerVehicleAndPerDriver() = runBlocking {
        fun shift(driver: String, vehicle: String) =
            ShiftEntity(UUID.randomUUID().toString(), DemoData.businessId.toString(), driver, device.toString(), vehicle, "2026-11-13", "OPEN", null, 0)
        // S4: same vehicle, different driver.
        assertSqlFails("CONFLICT") { runBlocking { db.stockDao().insertShift(shift(UUID.randomUUID().toString(), DemoData.vehicle.toString())) } }
        // Same driver, another vehicle.
        db.stockDao().insertLocation(bb.produce.invoicing.core.data.db.LocationEntity("van-2", DemoData.businessId.toString(), "VEHICLE", "Van 2"))
        assertSqlFails("CONFLICT") { runBlocking { db.stockDao().insertShift(shift(DemoData.driverUser.toString(), "van-2")) } }
        // Planned shifts do not conflict; a closed shift frees the vehicle.
        db.stockDao().insertShift(shift(UUID.randomUUID().toString(), DemoData.vehicle.toString()).copy(status = "PLANNED"))
        db.stockDao().setShiftStatus(DemoData.shift.toString(), "RECONCILED", null, 0)
        db.stockDao().insertShift(shift(UUID.randomUUID().toString(), DemoData.vehicle.toString()))
        assertEquals(3, count("shift"))
    }

    @Test
    fun foreignKeysAreEnforced() {
        assertSqlFails("FOREIGN KEY") {
            runBlocking {
                db.stockDao().insertMovements(listOf(
                    StockMovementEntity(UUID.randomUUID().toString(), DemoData.businessId.toString(), "no-such-shift", DemoData.vehicle.toString(),
                        DemoData.products[0].id.toString(), "TOP_UP", "SALEABLE", "1", "lb", null, null, null, null, null, "u", "OWNER", "d", 0),
                ))
            }
        }
        assertSqlFails("FOREIGN KEY") { sql("INSERT INTO payment_event VALUES ('p1','missing-invoice','MARKED_PAID','CASH',100,'u','d',0,NULL,NULL,NULL)") }
    }
}
