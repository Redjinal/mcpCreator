package bb.produce.invoicing.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.stock.NegativeStockPolicy
import bb.produce.invoicing.domain.stock.StockLedger
import bb.produce.invoicing.core.data.db.toDomain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import java.math.BigDecimal
import java.time.Instant

/**
 * Robolectric tests run Room on the JVM against a real SQLite engine (Robolectric NATIVE mode), so
 * constraints, triggers and transactions behave as SQLite does on a device. They do not replace the
 * on-device instrumentation run, which is BLOCKED until a phone/emulator is available.
 */
abstract class DbTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val t0: Instant = Instant.parse("2026-11-13T13:00:00Z")
    protected var clockNow: Instant = t0
    protected lateinit var db: AppDatabase
    protected lateinit var repo: InvoicingRepository
    protected var device: DeviceId = DeviceId(java.util.UUID(0, 0))
    protected lateinit var driver: Actor
    protected lateinit var owner: Actor
    protected open val dbName: String? = null // null = in-memory

    @Before
    fun setUpDb() = runBlocking {
        if (dbName != null) context.deleteDatabase(dbName)
        db = openDb()
        repo = InvoicingRepository(db, { clockNow })
        device = repo.deviceId()
        assertTrue(DemoSeeder(db).seedIfEmpty(device, t0))
        driver = Actor(DemoData.driverUser, Role.DRIVER, device)
        owner = Actor(DemoData.ownerUser, Role.OWNER, device)
    }

    @After
    fun tearDownDb() {
        db.close()
        if (dbName != null) context.deleteDatabase(dbName)
    }

    protected fun openDb(): AppDatabase = AppDatabase.builder(context, dbName).build()

    /** Simulates a process restart: close the database file and open it again. */
    protected fun restart(afterStep: (IssueStep) -> Unit = {}) {
        check(dbName != null) { "restart needs a file database" }
        db.close()
        db = openDb()
        repo = InvoicingRepository(db, { clockNow }, afterStep)
    }

    protected val tomatoes get() = DemoData.products[0]
    protected val limes get() = DemoData.products[1]
    protected val lb = UnitCode.of("lb")
    protected val case = UnitCode.of("case")

    /** 12.5 lb tomatoes @ 4.50 + 2 case limes @ 24.00 = 56.25 + 48.00 = BBD 104.25. */
    protected suspend fun savedDraft(r: InvoicingRepository = repo): InvoiceDraft {
        var d = r.newDraft(DemoData.businessId, DemoData.driverUser).withCustomer(DemoData.customers[0].id, t0)
        d = d.addProduct(tomatoes, lb, t0).getOrThrow()
        d = d.updateQuantity(d.lines[0].id, Quantity.of("12.5"), t0).getOrThrow()
        d = d.addProduct(limes, case, t0).getOrThrow()
        d = d.updateQuantity(d.lines[1].id, Quantity.of("2"), t0).getOrThrow()
        r.saveDraft(d).getOrThrow()
        return d
    }

    protected suspend fun saleableOf(productIndex: Int): BigDecimal {
        val ledger = db.stockDao().movements(DemoData.vehicle.toString()).fold(StockLedger.empty(DemoData.vehicle)) { l, m ->
            l.append(m.toDomain(), NegativeStockPolicy.ALLOW_AND_FLAG).getOrThrow()
        }
        return ledger.balance(DemoData.products[productIndex].id).saleable
    }

    protected fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    protected fun assertSqlFails(expectedFragment: String, block: () -> Unit) {
        try {
            block()
            fail("Expected SQLite failure containing '$expectedFragment'")
        } catch (e: SQLiteException) {
            assertTrue("Unexpected message: ${e.message}", e.message.orEmpty().contains(expectedFragment))
        }
    }

    protected fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    protected fun scalar(query: String): String? =
        db.openHelper.readableDatabase.query(query).use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
