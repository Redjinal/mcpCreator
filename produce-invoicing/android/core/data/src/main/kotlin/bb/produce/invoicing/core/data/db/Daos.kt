package bb.produce.invoicing.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Inserts of financial/ledger rows use OnConflictStrategy.ABORT (the default): a duplicate id or a
 * unique-constraint hit throws and rolls back the surrounding transaction. Nothing is silently replaced.
 */

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device_identity WHERE singleton = 1")
    suspend fun get(): DeviceIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(e: DeviceIdentityEntity)
}

@Dao
interface BusinessDao {
    @Insert suspend fun insert(e: BusinessEntity)
    @Query("SELECT * FROM business WHERE id = :id") suspend fun get(id: String): BusinessEntity?
    @Query("SELECT * FROM business ORDER BY id LIMIT 1") suspend fun first(): BusinessEntity?
}

@Dao
interface LeaseDao {
    @Insert suspend fun insert(e: DriverLeaseEntity)

    @Query("SELECT * FROM driver_lease WHERE business_id = :businessId AND driver_user_id = :driverUserId")
    suspend fun forDriver(businessId: String, driverUserId: String): DriverLeaseEntity?

    /**
     * Compare-and-set advance of the durable per-driver counter. Returns rows updated; anything other
     * than 1 means the lease changed underneath us (revoked, new epoch, concurrent issue) and the
     * issue transaction must roll back.
     */
    @Query(
        """UPDATE driver_lease SET next_sequence = :newNext, updated_at = :at
           WHERE business_id = :businessId AND driver_code = :driverCode AND epoch = :epoch
             AND revoked = 0 AND next_sequence = :expectedNext""",
    )
    suspend fun advance(businessId: String, driverCode: String, epoch: Long, expectedNext: Long, newNext: Long?, at: Long): Int

    @Query("UPDATE driver_lease SET revoked = 1, updated_at = :at WHERE business_id = :businessId AND driver_code = :driverCode")
    suspend fun revoke(businessId: String, driverCode: String, at: Long): Int

    @Query("UPDATE driver_lease SET driver_display_name = :name, updated_at = :at WHERE business_id = :businessId AND driver_code = :driverCode")
    suspend fun rename(businessId: String, driverCode: String, name: String, at: Long): Int
}

@Dao
interface CatalogueDao {
    @Insert suspend fun insertCustomer(e: CustomerEntity)
    @Upsert suspend fun upsertCustomer(e: CustomerEntity)
    @Insert suspend fun insertProduct(e: ProductEntity)
    @Upsert suspend fun upsertProduct(e: ProductEntity)
    @Insert suspend fun insertPrices(e: List<ProductUnitPriceEntity>)

    @Query("DELETE FROM product_unit_price WHERE product_id = :productId")
    suspend fun deletePrices(productId: String)

    @Query("SELECT * FROM customer WHERE id = :id") suspend fun customer(id: String): CustomerEntity?
    @Query("SELECT * FROM customer WHERE business_id = :businessId AND active = 1 ORDER BY name")
    fun observeCustomers(businessId: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM product WHERE id IN (:ids)") suspend fun products(ids: List<String>): List<ProductEntity>
    @Query("SELECT * FROM product_unit_price WHERE product_id IN (:ids) ORDER BY rowid") suspend fun prices(ids: List<String>): List<ProductUnitPriceEntity>
    @Query("SELECT * FROM product WHERE business_id = :businessId ORDER BY name") suspend fun allProducts(businessId: String): List<ProductEntity>
}

@Dao
interface DraftDao {
    @Upsert suspend fun upsert(e: DraftEntity)
    @Insert suspend fun insertLines(e: List<DraftLineEntity>)
    @Query("DELETE FROM draft_line WHERE draft_id = :draftId") suspend fun deleteLines(draftId: String)
    @Query("DELETE FROM draft WHERE id = :draftId") suspend fun delete(draftId: String): Int
    @Query("SELECT * FROM draft WHERE id = :id") suspend fun get(id: String): DraftEntity?
    @Query("SELECT * FROM draft_line WHERE draft_id = :draftId ORDER BY position") suspend fun lines(draftId: String): List<DraftLineEntity>
    @Query("SELECT * FROM draft WHERE driver_user_id = :driverUserId ORDER BY updated_at DESC")
    fun observeForDriver(driverUserId: String): Flow<List<DraftEntity>>
    @Query("SELECT COUNT(*) FROM draft") suspend fun count(): Int
}

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(e: InvoiceEntity)
    @Insert suspend fun insertLines(e: List<InvoiceLineEntity>)
    @Query("SELECT * FROM invoice WHERE id = :id") suspend fun get(id: String): InvoiceEntity?
    @Query("SELECT * FROM invoice_line WHERE invoice_id = :invoiceId ORDER BY position") suspend fun lines(invoiceId: String): List<InvoiceLineEntity>
    @Query("SELECT * FROM invoice WHERE source_draft_id = :draftId") suspend fun bySourceDraft(draftId: String): InvoiceEntity?
    @Query("SELECT * FROM invoice WHERE business_id = :businessId AND customer_invoice_number = :number")
    suspend fun byNumber(businessId: String, number: String): InvoiceEntity?
    @Query("SELECT * FROM invoice WHERE business_id = :businessId AND register_sequence = :sequence")
    suspend fun byRegisterSequence(businessId: String, sequence: Long): InvoiceEntity?

    /** Newest first by device issue time, then driver/sequence for a stable order. */
    @Query("SELECT * FROM invoice WHERE business_id = :businessId ORDER BY issued_at_device DESC, driver_code, driver_sequence DESC")
    fun observeAll(businessId: String): Flow<List<InvoiceEntity>>

    @Query(
        """UPDATE invoice SET register_business_code = :code, register_sequence = :sequence,
             first_received_at_server = :firstReceived, registered_at_server = :registered, sync_state = 'SYNCED'
           WHERE id = :id AND register_sequence IS NULL""",
    )
    suspend fun setRegistration(id: String, code: String, sequence: Long, firstReceived: Long, registered: Long): Int

    @Query(
        """UPDATE invoice SET void_reason = :reason, voided_by = :by, void_device_id = :device, voided_at = :at
           WHERE id = :id AND voided_at IS NULL""",
    )
    suspend fun setVoid(id: String, reason: String, by: String, device: String, at: Long): Int

    @Query("SELECT COUNT(*) FROM invoice") suspend fun count(): Int
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(e: AuditEventEntity)
    @Query("SELECT * FROM audit_event WHERE entity_id = :entityId ORDER BY at, rowid") suspend fun forEntity(entityId: String): List<AuditEventEntity>
    @Query("SELECT COUNT(*) FROM audit_event") suspend fun count(): Int
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(e: OutboxEventEntity)
    @Insert suspend fun insertAll(e: List<OutboxEventEntity>)
    @Query("SELECT * FROM outbox_event WHERE acked_at IS NULL ORDER BY created_at, rowid LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEventEntity>
    @Query("SELECT * FROM outbox_event WHERE aggregate_id = :aggregateId ORDER BY created_at, rowid")
    suspend fun forAggregate(aggregateId: String): List<OutboxEventEntity>
    @Query("UPDATE outbox_event SET acked_at = :at, next_attempt_at = NULL WHERE event_id = :eventId AND acked_at IS NULL")
    suspend fun acknowledge(eventId: String, at: Long): Int
    @Query("UPDATE outbox_event SET attempts = attempts + 1, next_attempt_at = :next WHERE event_id = :eventId AND acked_at IS NULL")
    suspend fun recordFailedAttempt(eventId: String, next: Long): Int
    @Query("SELECT COUNT(*) FROM outbox_event WHERE acked_at IS NULL") suspend fun pendingCount(): Int
}

@Dao
interface StockDao {
    @Insert suspend fun insertLocation(e: LocationEntity)
    @Insert suspend fun insertShift(e: ShiftEntity)
    @Query("UPDATE shift SET status = :status, status_reason = :reason, updated_at = :at WHERE id = :id")
    suspend fun setShiftStatus(id: String, status: String, reason: String?, at: Long): Int
    @Insert suspend fun insertConversions(e: List<StockConversionEntity>)
    @Insert suspend fun insertMovements(e: List<StockMovementEntity>)

    @Query(
        """SELECT * FROM shift WHERE driver_user_id = :driverUserId AND device_id = :deviceId AND status = 'OPEN'
           ORDER BY updated_at DESC LIMIT 1""",
    )
    suspend fun openShift(driverUserId: String, deviceId: String): ShiftEntity?

    @Query("SELECT * FROM stock_conversion WHERE product_id IN (:productIds)")
    suspend fun conversions(productIds: List<String>): List<StockConversionEntity>

    @Query("SELECT * FROM stock_movement WHERE location_id = :locationId ORDER BY at, rowid")
    suspend fun movements(locationId: String): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movement WHERE sale_invoice_id = :invoiceId ORDER BY rowid")
    suspend fun saleDebitsFor(invoiceId: String): List<StockMovementEntity>

    @Query("SELECT COUNT(*) FROM stock_movement") suspend fun movementCount(): Int
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(e: PaymentEventEntity)
    @Query("SELECT * FROM payment_event WHERE invoice_id = :invoiceId ORDER BY at, rowid") suspend fun forInvoice(invoiceId: String): List<PaymentEventEntity>
}

@Dao
interface PrintDao {
    @Insert suspend fun insertProfile(e: PrinterProfileEntity)
    @Insert suspend fun insertAttempt(e: PrintAttemptEntity)
    @Query("SELECT * FROM print_attempt WHERE invoice_id = :invoiceId ORDER BY created_at, rowid")
    suspend fun attempts(invoiceId: String): List<PrintAttemptEntity>
}
