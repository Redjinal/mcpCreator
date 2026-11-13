package bb.produce.invoicing.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = AppDatabase.VERSION,
    entities = [
        // v1
        DeviceIdentityEntity::class, BusinessEntity::class, DriverLeaseEntity::class, CustomerEntity::class,
        ProductEntity::class, ProductUnitPriceEntity::class, DraftEntity::class, DraftLineEntity::class,
        InvoiceEntity::class, InvoiceLineEntity::class, AuditEventEntity::class, OutboxEventEntity::class,
        // v2
        LocationEntity::class, ShiftEntity::class, StockConversionEntity::class, StockMovementEntity::class,
        PaymentEventEntity::class, ReturnIntakeEntity::class, StockCountEntity::class,
        PrinterProfileEntity::class, PrintAttemptEntity::class,
    ],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun businessDao(): BusinessDao
    abstract fun leaseDao(): LeaseDao
    abstract fun catalogueDao(): CatalogueDao
    abstract fun draftDao(): DraftDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun auditDao(): AuditDao
    abstract fun outboxDao(): OutboxDao
    abstract fun stockDao(): StockDao
    abstract fun paymentDao(): PaymentDao
    abstract fun printDao(): PrintDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "produce-invoicing.db"

        /**
         * Production builder. No destructive fallback: a missing migration must crash loudly rather
         * than silently wipe unsynced financial records.
         */
        fun builder(context: Context, name: String? = FILE_NAME): Builder<AppDatabase> {
            val b = if (name == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, name)
            return b.addMigrations(*Schema.MIGRATIONS).addCallback(Schema.TriggerCallback)
        }
    }
}

/** Re-asserts integrity triggers on every open (idempotent), including after migrations. */
internal fun SupportSQLiteDatabase.ensureTriggers() {
    Schema.TRIGGERS_V1.forEach(::execSQL)
    Schema.TRIGGERS_V2.forEach(::execSQL)
}
