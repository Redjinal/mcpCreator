package bb.produce.invoicing.core.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database-level integrity rules that Room annotations cannot express. They back up the domain and
 * repository checks so that even a buggy DAO call cannot rewrite issued financial history.
 * All statements are idempotent (`IF NOT EXISTS`) and are re-applied on every open.
 */
object Schema {

    private fun forbid(name: String, table: String, op: String, msg: String, `when`: String? = null) =
        "CREATE TRIGGER IF NOT EXISTS $name BEFORE $op ON $table " +
            (if (`when` != null) "WHEN $`when` " else "") +
            "BEGIN SELECT RAISE(ABORT, '$msg'); END"

    private fun changed(cols: List<String>) = cols.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }

    /** Invoice columns frozen at issue. Only registration (once), void (once) and sync_state may change. */
    val INVOICE_FROZEN_COLUMNS = listOf(
        "id", "source_draft_id", "business_id", "customer_invoice_number", "business_code", "driver_code",
        "driver_sequence", "biz_name", "biz_address", "biz_phone", "biz_email", "biz_footer", "currency",
        "customer_id", "cust_name", "cust_contact", "cust_phone", "cust_address", "driver_user_id",
        "driver_display_name", "issued_by", "device_id", "lease_epoch", "catalogue_version", "rounding_policy_id",
        "tax_kind", "tax_config_version", "subtotal_minor", "tax_minor", "total_minor", "issued_at_device",
    )
    private val REGISTER_COLUMNS = listOf("register_business_code", "register_sequence", "first_received_at_server", "registered_at_server")
    private val VOID_COLUMNS = listOf("void_reason", "voided_by", "void_device_id", "voided_at")
    private const val ACTIVE_SHIFT = "('LOADED','OPEN','CLOSING_PENDING_SYNC')"

    val TRIGGERS_V1: List<String> = listOf(
        forbid("invoice_no_delete", "invoice", "DELETE", "IMMUTABLE: issued invoices cannot be deleted"),
        forbid("invoice_frozen", "invoice", "UPDATE", "IMMUTABLE: issued invoice snapshot cannot change", changed(INVOICE_FROZEN_COLUMNS)),
        forbid("invoice_register_once", "invoice", "UPDATE", "IMMUTABLE: register reference is set once",
            "OLD.register_sequence IS NOT NULL AND (${changed(REGISTER_COLUMNS)})"),
        forbid("invoice_register_complete", "invoice", "UPDATE", "INVALID: register reference incomplete or wrong prefix",
            "NEW.register_sequence IS NOT NULL AND (NEW.register_business_code IS NOT NEW.business_code " +
                "OR NEW.first_received_at_server IS NULL OR NEW.registered_at_server IS NULL)"),
        forbid("invoice_void_once", "invoice", "UPDATE", "IMMUTABLE: void is final",
            "OLD.voided_at IS NOT NULL AND (${changed(VOID_COLUMNS)})"),
        forbid("invoice_line_no_update", "invoice_line", "UPDATE", "IMMUTABLE: issued invoice lines cannot change"),
        forbid("invoice_line_no_delete", "invoice_line", "DELETE", "IMMUTABLE: issued invoice lines cannot be deleted"),
        forbid("audit_no_update", "audit_event", "UPDATE", "APPEND-ONLY: audit_event"),
        forbid("audit_no_delete", "audit_event", "DELETE", "APPEND-ONLY: audit_event"),
        forbid("lease_identity_fixed", "driver_lease", "UPDATE", "IMMUTABLE: driver code identity",
            changed(listOf("business_id", "driver_code", "driver_user_id"))),
        forbid("lease_epoch_monotonic", "driver_lease", "UPDATE", "FENCING: lease epoch cannot decrease", "NEW.epoch < OLD.epoch"),
        forbid("lease_sequence_monotonic", "driver_lease", "UPDATE", "NUMBERING: driver sequence cannot go backwards",
            "OLD.next_sequence IS NOT NULL AND NEW.next_sequence IS NOT NULL AND NEW.next_sequence < OLD.next_sequence"),
        forbid("lease_no_delete", "driver_lease", "DELETE", "NUMBERING: driver leases are never deleted"),
    )

    val TRIGGERS_V2: List<String> = listOf(
        forbid("stock_movement_no_update", "stock_movement", "UPDATE", "APPEND-ONLY: stock_movement"),
        forbid("stock_movement_no_delete", "stock_movement", "DELETE", "APPEND-ONLY: stock_movement"),
        forbid("payment_event_no_update", "payment_event", "UPDATE", "APPEND-ONLY: payment_event"),
        forbid("payment_event_no_delete", "payment_event", "DELETE", "APPEND-ONLY: payment_event"),
        forbid("return_intake_no_update", "return_intake", "UPDATE", "APPEND-ONLY: return_intake"),
        forbid("return_intake_no_delete", "return_intake", "DELETE", "APPEND-ONLY: return_intake"),
        forbid("stock_count_no_update", "stock_count", "UPDATE", "APPEND-ONLY: stock_count"),
        forbid("stock_count_no_delete", "stock_count", "DELETE", "APPEND-ONLY: stock_count"),
        forbid("print_attempt_no_delete", "print_attempt", "DELETE", "APPEND-ONLY: print_attempt"),
        forbid("shift_one_active_per_vehicle_ins", "shift", "INSERT", "CONFLICT: vehicle already has an active shift",
            "NEW.status IN $ACTIVE_SHIFT AND EXISTS (SELECT 1 FROM shift s WHERE s.vehicle_location_id = NEW.vehicle_location_id AND s.status IN $ACTIVE_SHIFT)"),
        forbid("shift_one_active_per_vehicle_upd", "shift", "UPDATE", "CONFLICT: vehicle already has an active shift",
            "NEW.status IN $ACTIVE_SHIFT AND EXISTS (SELECT 1 FROM shift s WHERE s.vehicle_location_id = NEW.vehicle_location_id AND s.status IN $ACTIVE_SHIFT AND s.id <> NEW.id)"),
        forbid("shift_one_active_per_driver_ins", "shift", "INSERT", "CONFLICT: driver already has an active shift",
            "NEW.status IN $ACTIVE_SHIFT AND EXISTS (SELECT 1 FROM shift s WHERE s.driver_user_id = NEW.driver_user_id AND s.status IN $ACTIVE_SHIFT)"),
        forbid("shift_one_active_per_driver_upd", "shift", "UPDATE", "CONFLICT: driver already has an active shift",
            "NEW.status IN $ACTIVE_SHIFT AND EXISTS (SELECT 1 FROM shift s WHERE s.driver_user_id = NEW.driver_user_id AND s.status IN $ACTIVE_SHIFT AND s.id <> NEW.id)"),
    )

    /** v1 → v2: add the approved V2 tables (docs/09) and the business negative-stock policy. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
            TRIGGERS_V2.forEach(db::execSQL)
        }
    }

    val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

    object TriggerCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.ensureTriggers()
        }
    }
}
