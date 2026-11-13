package bb.produce.invoicing.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v1 — core invoicing. Conventions:
 *  - UUIDs are TEXT; instants are UTC epoch milliseconds (INTEGER); money is integer cents;
 *    quantities are exact decimal TEXT (never REAL).
 *  - Issued invoices, their lines and the audit log are made immutable by SQLite triggers (Schema.kt).
 */

/** This installation's permanent device id (single row). */
@Entity(tableName = "device_identity")
data class DeviceIdentityEntity(
    @PrimaryKey val singleton: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "business")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val address: String,
    val phone: String,
    val email: String?,
    @ColumnInfo(name = "invoice_footer") val invoiceFooter: String?,
    val currency: String,
    @ColumnInfo(name = "tax_config_version") val taxConfigVersion: Int,
    @ColumnInfo(name = "catalogue_version") val catalogueVersion: Int,
    /** v2. Client gate 5: BLOCK or ALLOW_AND_FLAG. Migration default is the conservative BLOCK. */
    @ColumnInfo(name = "negative_stock_policy", defaultValue = "BLOCK") val negativeStockPolicy: String = "BLOCK",
)

/**
 * Fenced per-driver issuing lease (docs/11). `next_sequence` is the durable counter; triggers forbid
 * it from decreasing and forbid changing the driver's identity.
 */
@Entity(
    tableName = "driver_lease",
    primaryKeys = ["business_id", "driver_code"],
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [Index(value = ["business_id", "driver_user_id"], unique = true)],
)
data class DriverLeaseEntity(
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "driver_code") val driverCode: String,
    @ColumnInfo(name = "driver_user_id") val driverUserId: String,
    @ColumnInfo(name = "driver_display_name") val driverDisplayName: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val epoch: Long,
    /** Null when the sequence is exhausted. */
    @ColumnInfo(name = "next_sequence") val nextSequence: Long?,
    val revoked: Boolean,
    @ColumnInfo(name = "valid_until") val validUntil: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "customer",
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [Index("business_id"), Index("name")],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val name: String,
    val contact: String?,
    val phone: String?,
    val address: String?,
    val active: Boolean,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String?,
    @ColumnInfo(name = "server_revision") val serverRevision: Long?,
)

@Entity(
    tableName = "product",
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [Index(value = ["business_id", "sku"], unique = true)],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val sku: String,
    val name: String,
    val active: Boolean,
)

@Entity(
    tableName = "product_unit_price",
    primaryKeys = ["product_id", "unit"],
    foreignKeys = [ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"], onDelete = ForeignKey.CASCADE)],
)
data class ProductUnitPriceEntity(
    @ColumnInfo(name = "product_id") val productId: String,
    val unit: String,
    /** Null = price entered on the invoice. */
    @ColumnInfo(name = "price_minor") val priceMinor: Long?,
)

@Entity(
    tableName = "draft",
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [Index("business_id"), Index("driver_user_id")],
)
data class DraftEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "driver_user_id") val driverUserId: String,
    @ColumnInfo(name = "customer_id") val customerId: String?,
    @ColumnInfo(name = "catalogue_version") val catalogueVersion: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "draft_line",
    foreignKeys = [ForeignKey(entity = DraftEntity::class, parentColumns = ["id"], childColumns = ["draft_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["draft_id", "position"], unique = true)],
)
data class DraftLineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    val position: Int,
    @ColumnInfo(name = "product_id") val productId: String?,
    val description: String,
    val unit: String,
    val quantity: String?,
    @ColumnInfo(name = "unit_price_minor") val unitPriceMinor: Long?,
    @ColumnInfo(name = "catalogue_unit_price_minor") val catalogueUnitPriceMinor: Long?,
    @ColumnInfo(name = "price_edited") val priceEdited: Boolean,
    @ColumnInfo(name = "zero_price_confirmed") val zeroPriceConfirmed: Boolean,
)

/**
 * Issued invoice snapshot. Uniqueness (docs/11): (business_id, driver_code, driver_sequence),
 * (business_id, customer_invoice_number), (business_id, register_sequence) — NULLs allowed until
 * registered — and source_draft_id (one invoice per draft: double-tap protection, N18).
 */
@Entity(
    tableName = "invoice",
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [
        Index(value = ["business_id", "driver_code", "driver_sequence"], unique = true),
        Index(value = ["business_id", "customer_invoice_number"], unique = true),
        Index(value = ["business_id", "register_sequence"], unique = true),
        Index(value = ["source_draft_id"], unique = true),
        Index("issued_at_device"),
        Index("driver_user_id"),
        Index("sync_state"),
    ],
)
data class InvoiceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_draft_id") val sourceDraftId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "customer_invoice_number") val customerInvoiceNumber: String,
    @ColumnInfo(name = "business_code") val businessCode: String,
    @ColumnInfo(name = "driver_code") val driverCode: String,
    @ColumnInfo(name = "driver_sequence") val driverSequence: Long,
    @ColumnInfo(name = "biz_name") val bizName: String,
    @ColumnInfo(name = "biz_address") val bizAddress: String,
    @ColumnInfo(name = "biz_phone") val bizPhone: String,
    @ColumnInfo(name = "biz_email") val bizEmail: String?,
    @ColumnInfo(name = "biz_footer") val bizFooter: String?,
    val currency: String,
    @ColumnInfo(name = "customer_id") val customerId: String,
    @ColumnInfo(name = "cust_name") val custName: String,
    @ColumnInfo(name = "cust_contact") val custContact: String?,
    @ColumnInfo(name = "cust_phone") val custPhone: String?,
    @ColumnInfo(name = "cust_address") val custAddress: String?,
    @ColumnInfo(name = "driver_user_id") val driverUserId: String,
    @ColumnInfo(name = "driver_display_name") val driverDisplayName: String,
    @ColumnInfo(name = "issued_by") val issuedBy: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "lease_epoch") val leaseEpoch: Long,
    @ColumnInfo(name = "catalogue_version") val catalogueVersion: Int,
    @ColumnInfo(name = "rounding_policy_id") val roundingPolicyId: String,
    @ColumnInfo(name = "tax_kind") val taxKind: String,
    @ColumnInfo(name = "tax_config_version") val taxConfigVersion: Int,
    @ColumnInfo(name = "subtotal_minor") val subtotalMinor: Long,
    @ColumnInfo(name = "tax_minor") val taxMinor: Long,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "issued_at_device") val issuedAtDevice: Long,
    @ColumnInfo(name = "register_business_code") val registerBusinessCode: String?,
    @ColumnInfo(name = "register_sequence") val registerSequence: Long?,
    @ColumnInfo(name = "first_received_at_server") val firstReceivedAtServer: Long?,
    @ColumnInfo(name = "registered_at_server") val registeredAtServer: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "voided_by") val voidedBy: String?,
    @ColumnInfo(name = "void_device_id") val voidDeviceId: String?,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "sync_state") val syncState: String,
)

@Entity(
    tableName = "invoice_line",
    foreignKeys = [ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoice_id"])],
    indices = [Index(value = ["invoice_id", "position"], unique = true)],
)
data class InvoiceLineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String,
    val position: Int,
    @ColumnInfo(name = "product_id") val productId: String?,
    val description: String,
    val unit: String,
    val quantity: String,
    @ColumnInfo(name = "unit_price_minor") val unitPriceMinor: Long,
    @ColumnInfo(name = "line_total_minor") val lineTotalMinor: Long,
    @ColumnInfo(name = "catalogue_unit_price_minor") val catalogueUnitPriceMinor: Long?,
    @ColumnInfo(name = "zero_price_confirmed") val zeroPriceConfirmed: Boolean,
)

@Entity(tableName = "audit_event", indices = [Index("entity_id"), Index("at")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val action: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val at: Long,
    /** Identifiers/codes only, as `key=value` lines. No PII. */
    val details: String,
)

/** Transactional outbox: written in the same transaction as the change; `event_id` is the upload idempotency key. */
@Entity(tableName = "outbox_event", indices = [Index(value = ["acked_at", "created_at"]), Index("aggregate_id")])
data class OutboxEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val type: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "payload_schema_version") val payloadSchemaVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "acked_at") val ackedAt: Long?,
)
