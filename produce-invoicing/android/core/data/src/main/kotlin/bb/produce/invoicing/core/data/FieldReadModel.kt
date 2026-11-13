package bb.produce.invoicing.core.data

import androidx.room.withTransaction
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.core.data.db.AuditEventEntity
import bb.produce.invoicing.core.data.db.DraftRow
import bb.produce.invoicing.core.data.db.InvoiceRow
import bb.produce.invoicing.core.data.db.OutboxEventEntity
import bb.produce.invoicing.core.data.db.PrinterProfileEntity
import bb.produce.invoicing.core.data.db.instant
import bb.produce.invoicing.core.data.db.toDomain
import bb.produce.invoicing.core.data.db.toEntity
import bb.produce.invoicing.core.data.db.toProduct
import bb.produce.invoicing.domain.audit.AuditAction
import bb.produce.invoicing.domain.audit.OutboxEventType
import bb.produce.invoicing.domain.catalogue.Business
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.identity.DriverSequenceLease
import bb.produce.invoicing.domain.invoice.DraftId
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.payment.PaymentStatus
import bb.produce.invoicing.domain.printing.PrintStatus
import bb.produce.invoicing.domain.stock.NegativeStockPolicy
import bb.produce.invoicing.domain.stock.StockBalance
import bb.produce.invoicing.domain.stock.StockLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One row of the invoice list. Issue, sync, print and payment status are separate on purpose (docs/06). */
data class InvoiceListItem(
    val id: InvoiceId,
    val number: String,
    /** Internal office register reference `PRE-N`; null until the server registers the invoice. */
    val registerReference: String?,
    val customerId: CustomerId,
    val customerName: String,
    val driverCode: String,
    val driverName: String,
    val total: Money,
    val issuedAtDevice: Instant,
    val voided: Boolean,
    val syncState: SyncState,
    val paymentStatus: PaymentStatus,
    val printStatus: PrintStatus,
) {
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOfNotNull(number, registerReference, customerName, driverName, driverCode, id.toString())
            .any { it.contains(q, ignoreCase = true) }
    }
}

data class ShiftContext(val shiftId: ShiftId, val vehicle: LocationId, val vehicleName: String, val localDate: String, val status: String)

enum class CustomerError { NAME_REQUIRED, DUPLICATE_NAME, INVALID_PHONE, NOT_AUTHORIZED }

/**
 * Read side for the screens plus small write commands that are not part of invoice issue.
 * Visibility is enforced HERE (invariant 6): a driver only ever receives their own invoices.
 */
class FieldReadModel(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    suspend fun business(): Business? = db.businessDao().first()?.toDomain()
    suspend fun negativeStockPolicy(): NegativeStockPolicy? = db.businessDao().first()?.let { NegativeStockPolicy.valueOf(it.negativeStockPolicy) }
    suspend fun catalogueVersion(): Int? = db.businessDao().first()?.catalogueVersion

    fun invoiceRows(viewer: Actor, businessId: BusinessId): Flow<List<InvoiceListItem>> =
        db.invoiceDao().observeRows(businessId.toString(), if (viewer.role == Role.OWNER) null else viewer.userId.toString())
            .map { rows -> rows.map { it.toItem() } }

    /** Null when the invoice does not exist OR the viewer may not see it (drivers see only their own). */
    suspend fun invoiceFor(viewer: Actor, repo: InvoicingRepository, id: InvoiceId) =
        repo.invoice(id)?.takeIf { viewer.role == Role.OWNER || it.driver.userId == viewer.userId }

    fun observeInvoiceChanges(id: InvoiceId): Flow<Any?> =
        combine(db.invoiceDao().observe(id.toString()), db.paymentDao().observeForInvoice(id.toString())) { a, b -> a to b }

    fun draftRows(viewer: Actor): Flow<List<DraftRow>> = db.draftDao().observeRows(viewer.userId.toString())

    fun customers(businessId: BusinessId): Flow<List<Customer>> =
        db.catalogueDao().observeCustomers(businessId.toString()).map { l -> l.map { it.toDomain() } }

    /** Active and inactive products with their unit prices, sorted by name. */
    fun products(businessId: BusinessId): Flow<List<Product>> =
        combine(db.catalogueDao().observeProducts(businessId.toString()), db.catalogueDao().observePrices()) { products, prices ->
            val byProduct = prices.groupBy { it.productId }
            products.mapNotNull { p -> byProduct[p.id]?.let { toProduct(p, it) } }
        }

    fun conversions(): Flow<ConversionTable> = db.stockDao().observeConversions().map { l -> ConversionTable(l.map { it.toDomain() }) }

    suspend fun shiftContext(viewer: Actor): ShiftContext? {
        val shift = db.stockDao().openShift(viewer.userId.toString(), viewer.deviceId.toString()) ?: return null
        val loc = db.stockDao().location(shift.vehicleLocationId)
        return ShiftContext(ShiftId(UUID.fromString(shift.id)), LocationId(UUID.fromString(shift.vehicleLocationId)),
            loc?.name ?: "Vehicle", shift.localDate, shift.status)
    }

    /** Saleable/quarantined balance per product on a vehicle, derived from the append-only ledger. */
    fun stockBalances(vehicle: LocationId): Flow<Map<ProductId, StockBalance>> =
        db.stockDao().observeMovements(vehicle.toString()).map { rows ->
            val ledger = rows.fold(StockLedger.empty(vehicle)) { l, m -> l.append(m.toDomain(), NegativeStockPolicy.ALLOW_AND_FLAG).getOrThrow() }
            rows.map { ProductId(UUID.fromString(it.productId)) }.distinct().associateWith { ledger.balance(it) }
        }

    suspend fun issuingLease(viewer: Actor): DriverSequenceLease? {
        val business = db.businessDao().first() ?: return null
        return db.leaseDao().forDriver(business.id, viewer.userId.toString())?.toDomain()?.takeIf { it.deviceId == viewer.deviceId }
    }

    fun pendingOutboxCount(): Flow<Int> = db.outboxDao().observePendingCount()
    fun printerProfiles(): Flow<List<PrinterProfileEntity>> = db.printDao().observeProfiles()

    /**
     * Creates a customer offline (docs/02 P0). Duplicate names (case-insensitive) are refused locally;
     * cross-device duplicates are reviewed at sync (Task 05), never silently merged.
     */
    suspend fun createCustomer(
        businessId: BusinessId, actor: Actor, name: String, phone: String?, address: String?,
        id: CustomerId = CustomerId(UUID.randomUUID()),
    ): Outcome<Customer, CustomerError> = db.withTransaction {
        val n = name.trim()
        if (n.isEmpty()) return@withTransaction Outcome.Err(CustomerError.NAME_REQUIRED)
        val p = phone?.trim()?.takeIf { it.isNotEmpty() }
        if (p != null && !PHONE.matches(p)) return@withTransaction Outcome.Err(CustomerError.INVALID_PHONE)
        if (db.catalogueDao().customerByName(businessId.toString(), n) != null) return@withTransaction Outcome.Err(CustomerError.DUPLICATE_NAME)
        val customer = Customer(id, businessId, n, phone = p, address = address?.trim()?.takeIf { it.isNotEmpty() })
        db.catalogueDao().insertCustomer(customer.toEntity(actor.deviceId))
        val at = now()
        db.auditDao().insert(AuditEventEntity(EventId.random().toString(), businessId.toString(), actor.userId.toString(), actor.deviceId.toString(),
            AuditAction.CATALOGUE_CHANGED.name, "customer", id.toString(), at.toEpochMilli(), "change=customer_created"))
        db.outboxDao().insert(OutboxEventEntity(EventId.random().toString(), businessId.toString(), OutboxEventType.CUSTOMER_CREATED.name,
            id.toString(), 1, at.toEpochMilli(), 0, null, null))
        Outcome.Ok(customer)
    }

    suspend fun deleteDraftIfEmpty(id: DraftId) {
        val lines = db.draftDao().lines(id.toString())
        val d = db.draftDao().get(id.toString()) ?: return
        if (lines.isEmpty() && d.customerId == null) db.draftDao().delete(id.toString())
    }

    private fun InvoiceRow.toItem() = InvoiceListItem(
        id = InvoiceId(UUID.fromString(id)),
        number = number,
        registerReference = if (registerBusinessCode != null && registerSequence != null) "$registerBusinessCode-$registerSequence" else null,
        customerId = CustomerId(UUID.fromString(customerId)),
        customerName = customerName,
        driverCode = driverCode,
        driverName = driverName,
        total = Money.ofMinor(totalMinor),
        issuedAtDevice = issuedAtDevice.instant(),
        voided = voidedAt != null,
        syncState = SyncState.valueOf(syncState),
        paymentStatus = if (paidBalance > 0) PaymentStatus.PAID else PaymentStatus.UNPAID,
        printStatus = when {
            confirmedPrints > 0 -> PrintStatus.CONFIRMED
            lastPrintStatus != null -> PrintStatus.valueOf(lastPrintStatus)
            else -> PrintStatus.NOT_PRINTED
        },
    )

    companion object {
        /** v4 rule: digits, spaces, dashes, brackets, optional leading +, at least 6 characters. */
        private val PHONE = Regex("""^[+\d][\d\s\-()]{5,}$""")
    }
}
