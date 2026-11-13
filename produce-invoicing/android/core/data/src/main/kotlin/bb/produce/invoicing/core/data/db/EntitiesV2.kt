package bb.produce.invoicing.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v2 — approved V2 scope (docs/09): locations, shifts, stock conversions, append-only stock
 * movements, payment events, return intake, stock counts, printer profiles and print attempts.
 * Added by MIGRATION_1_2.
 */

@Entity(
    tableName = "location",
    foreignKeys = [ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"])],
    indices = [Index("business_id")],
)
data class LocationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    /** WAREHOUSE or VEHICLE. */
    val kind: String,
    val name: String,
)

/** One active (LOADED/OPEN/CLOSING) shift per vehicle and per driver — enforced by triggers. */
@Entity(
    tableName = "shift",
    foreignKeys = [
        ForeignKey(entity = BusinessEntity::class, parentColumns = ["id"], childColumns = ["business_id"]),
        ForeignKey(entity = LocationEntity::class, parentColumns = ["id"], childColumns = ["vehicle_location_id"]),
    ],
    indices = [Index("business_id"), Index("vehicle_location_id"), Index(value = ["driver_user_id", "status"])],
)
data class ShiftEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "driver_user_id") val driverUserId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "vehicle_location_id") val vehicleLocationId: String,
    /** Barbados local business date, ISO yyyy-MM-dd. */
    @ColumnInfo(name = "local_date") val localDate: String,
    val status: String,
    @ColumnInfo(name = "status_reason") val statusReason: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "stock_conversion",
    primaryKeys = ["product_id", "sell_unit"],
    foreignKeys = [ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"])],
)
data class StockConversionEntity(
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "sell_unit") val sellUnit: String,
    @ColumnInfo(name = "base_unit") val baseUnit: String,
    /** Exact decimal text. */
    val factor: String,
    val version: Int,
)

/**
 * Append-only stock ledger. UNIQUE(sale_invoice_id, sale_line_id) = one SALE_DEBIT per invoice line
 * (NULLs for other kinds); UNIQUE(reverses_movement_id) = a movement can be reversed once.
 */
@Entity(
    tableName = "stock_movement",
    foreignKeys = [
        ForeignKey(entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shift_id"]),
        ForeignKey(entity = LocationEntity::class, parentColumns = ["id"], childColumns = ["location_id"]),
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["sale_invoice_id"]),
    ],
    indices = [
        Index(value = ["sale_invoice_id", "sale_line_id"], unique = true),
        Index(value = ["reverses_movement_id"], unique = true),
        Index(value = ["location_id", "product_id"]),
        Index("shift_id"),
    ],
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "shift_id") val shiftId: String,
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val kind: String,
    val bucket: String,
    val quantity: String,
    val unit: String,
    @ColumnInfo(name = "sale_invoice_id") val saleInvoiceId: String?,
    @ColumnInfo(name = "sale_line_id") val saleLineId: String?,
    @ColumnInfo(name = "conversion_version") val conversionVersion: Int?,
    @ColumnInfo(name = "reverses_movement_id") val reversesMovementId: String?,
    val reason: String?,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "actor_role") val actorRole: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val at: Long,
)

/** Append-only payment facts; a reversal references the PAID event it corrects (once). */
@Entity(
    tableName = "payment_event",
    foreignKeys = [ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoice_id"])],
    indices = [Index("invoice_id"), Index(value = ["reverses_event_id"], unique = true)],
)
data class PaymentEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String,
    val kind: String,
    val method: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "recorded_by") val recordedBy: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val at: Long,
    @ColumnInfo(name = "external_reference") val externalReference: String?,
    @ColumnInfo(name = "reverses_event_id") val reversesEventId: String?,
    val reason: String?,
)

/** Physical return intake. Never edits the invoice; not a refund or credit note. */
@Entity(
    tableName = "return_intake",
    foreignKeys = [
        ForeignKey(entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shift_id"]),
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoice_id"]),
    ],
    indices = [Index("shift_id"), Index("invoice_id")],
)
data class ReturnIntakeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "shift_id") val shiftId: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String?,
    @ColumnInfo(name = "invoice_line_id") val invoiceLineId: String?,
    @ColumnInfo(name = "product_id") val productId: String,
    val quantity: String,
    val unit: String,
    val condition: String,
    val reason: String,
    val disposition: String,
    @ColumnInfo(name = "recorded_by") val recordedBy: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val at: Long,
)

@Entity(
    tableName = "stock_count",
    foreignKeys = [ForeignKey(entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shift_id"])],
    indices = [Index(value = ["shift_id", "product_id"])],
)
data class StockCountEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "shift_id") val shiftId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val counted: String,
    val expected: String,
    val variance: String,
    val unit: String,
    val reason: String?,
    @ColumnInfo(name = "counted_by") val countedBy: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val at: Long,
)

@Entity(tableName = "printer_profile")
data class PrinterProfileEntity(
    @PrimaryKey val id: String,
    val manufacturer: String,
    val model: String,
    val firmware: String?,
    @ColumnInfo(name = "paper_width_mm") val paperWidthMm: Int,
    @ColumnInfo(name = "printable_dots") val printableDots: Int,
    @ColumnInfo(name = "chars_per_line") val charsPerLine: Int,
    @ColumnInfo(name = "code_page") val codePage: String,
    val transport: String,
    @ColumnInfo(name = "supports_cut") val supportsCut: Boolean,
    @ColumnInfo(name = "supports_status_query") val supportsStatusQuery: Boolean,
    /** Null = Unverified. */
    @ColumnInfo(name = "certification_ref") val certificationRef: String?,
    @ColumnInfo(name = "certified_at") val certifiedAt: Long?,
    @ColumnInfo(name = "tested_on") val testedOn: String?,
)

@Entity(
    tableName = "print_attempt",
    foreignKeys = [
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoice_id"]),
        ForeignKey(entity = PrinterProfileEntity::class, parentColumns = ["id"], childColumns = ["printer_profile_id"]),
    ],
    indices = [Index("invoice_id"), Index("printer_profile_id")],
)
data class PrintAttemptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String,
    @ColumnInfo(name = "printer_profile_id") val printerProfileId: String,
    val transport: String,
    @ColumnInfo(name = "copy_number") val copyNumber: Int,
    @ColumnInfo(name = "operator_id") val operatorId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: String,
    @ColumnInfo(name = "reprint_reason") val reprintReason: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
