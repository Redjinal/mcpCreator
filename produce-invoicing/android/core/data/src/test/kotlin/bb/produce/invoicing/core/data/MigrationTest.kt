package bb.produce.invoicing.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.core.data.db.Schema
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Builds a real v1 database from `schemas/.../1.json`, fills it with a v1-era issued invoice and
 * pending outbox, migrates to v2 and validates the result against `2.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val name = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        for ((k, v) in pairs) when (v) {
            null -> putNull(k)
            is String -> put(k, v)
            is Long -> put(k, v)
            is Int -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            else -> error("unsupported $v")
        }
    }

    @Test
    fun migrate1To2PreservesInvoicesLeaseAndOutbox() {
        val biz = DemoData.businessId.toString()
        val invoiceId = UUID.randomUUID().toString()
        context.deleteDatabase(name)

        helper.createDatabase(name, 1).apply {
            insert("business", SQLiteDatabase.CONFLICT_ABORT, cv("id" to biz, "code" to "DEMO", "name" to "Demo Produce Co (SYNTHETIC)",
                "address" to "x", "phone" to "x", "email" to null, "invoice_footer" to null, "currency" to "BBD",
                "tax_config_version" to 1, "catalogue_version" to 1))
            insert("driver_lease", SQLiteDatabase.CONFLICT_ABORT, cv("business_id" to biz, "driver_code" to "AB",
                "driver_user_id" to DemoData.driverUser.toString(), "driver_display_name" to "Demo Driver (synthetic)", "device_id" to "dev-1",
                "epoch" to 1L, "next_sequence" to 101L, "revoked" to false, "valid_until" to null, "updated_at" to 0L))
            insert("invoice", SQLiteDatabase.CONFLICT_ABORT, cv(
                "id" to invoiceId, "source_draft_id" to UUID.randomUUID().toString(), "business_id" to biz,
                "customer_invoice_number" to "DEMO-100-AB", "business_code" to "DEMO", "driver_code" to "AB", "driver_sequence" to 100L,
                "biz_name" to "Demo Produce Co (SYNTHETIC)", "biz_address" to "x", "biz_phone" to "x", "biz_email" to null, "biz_footer" to null,
                "currency" to "BBD", "customer_id" to DemoData.customers[0].id.toString(), "cust_name" to "Sample Grocer A (synthetic)",
                "cust_contact" to null, "cust_phone" to null, "cust_address" to null,
                "driver_user_id" to DemoData.driverUser.toString(), "driver_display_name" to "Demo Driver (synthetic)",
                "issued_by" to DemoData.driverUser.toString(), "device_id" to UUID.randomUUID().toString(), "lease_epoch" to 1L,
                "catalogue_version" to 1, "rounding_policy_id" to "LINE_HALF_UP_CENTS_V1", "tax_kind" to "NOT_APPLIED", "tax_config_version" to 1,
                "subtotal_minor" to 5625L, "tax_minor" to 0L, "total_minor" to 5625L, "issued_at_device" to 1_000L,
                "register_business_code" to null, "register_sequence" to null, "first_received_at_server" to null, "registered_at_server" to null,
                "void_reason" to null, "voided_by" to null, "void_device_id" to null, "voided_at" to null, "sync_state" to "PENDING"))
            insert("invoice_line", SQLiteDatabase.CONFLICT_ABORT, cv("id" to UUID.randomUUID().toString(), "invoice_id" to invoiceId,
                "position" to 1, "product_id" to null, "description" to "Tomatoes", "unit" to "lb", "quantity" to "12.500",
                "unit_price_minor" to 450L, "line_total_minor" to 5625L, "catalogue_unit_price_minor" to 450L, "zero_price_confirmed" to false))
            insert("outbox_event", SQLiteDatabase.CONFLICT_ABORT, cv("event_id" to UUID.randomUUID().toString(), "business_id" to biz,
                "type" to "INVOICE_ISSUED", "aggregate_id" to invoiceId, "payload_schema_version" to 1, "created_at" to 1_000L,
                "attempts" to 3, "next_attempt_at" to 5_000L, "acked_at" to null))
            close()
        }

        // Validates every table, column, index and foreign key against 2.json.
        helper.runMigrationsAndValidate(name, 2, true, Schema.MIGRATION_1_2).apply {
            query("SELECT negative_stock_policy FROM business").use { c -> c.moveToFirst(); assertEquals("BLOCK", c.getString(0)) }
            query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'stock_movement_%'").use { c ->
                c.moveToFirst(); assertEquals(2, c.getInt(0))
            }
            close()
        }

        // Open with the production builder: data intact, v1 triggers (re)asserted, app can read and keep issuing.
        val db = AppDatabase.builder(context, name).build()
        try {
            val repo = InvoicingRepository(db)
            runBlocking {
                val inv = repo.invoice(InvoiceId(UUID.fromString(invoiceId)))!!
                assertEquals("DEMO-100-AB", inv.number.text)
                assertEquals(5625L, inv.total.minor)
                assertEquals(1, repo.pendingOutbox().size)
                assertEquals(3, repo.pendingOutbox().single().attempts)
                assertEquals(101L, db.leaseDao().forDriver(biz, DemoData.driverUser.toString())!!.nextSequence)
            }
            try {
                db.openHelper.writableDatabase.execSQL("UPDATE invoice SET total_minor = 1")
                fail("immutability trigger missing after migration")
            } catch (e: SQLiteException) {
                assertTrue(e.message!!.contains("IMMUTABLE"))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
        assertEquals(Role.DRIVER, Role.valueOf("DRIVER"))
    }
}
