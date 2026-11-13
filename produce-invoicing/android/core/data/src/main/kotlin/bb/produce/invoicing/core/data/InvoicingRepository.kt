package bb.produce.invoicing.core.data

import androidx.room.withTransaction
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.core.data.db.AuditEventEntity
import bb.produce.invoicing.core.data.db.DeviceIdentityEntity
import bb.produce.invoicing.core.data.db.OutboxEventEntity
import bb.produce.invoicing.core.data.db.driverSnapshot
import bb.produce.invoicing.core.data.db.millis
import bb.produce.invoicing.core.data.db.toDomain
import bb.produce.invoicing.core.data.db.toDraft
import bb.produce.invoicing.core.data.db.toEntities
import bb.produce.invoicing.core.data.db.toEntity
import bb.produce.invoicing.core.data.db.toIssuedInvoice
import bb.produce.invoicing.core.data.db.toProduct
import bb.produce.invoicing.domain.audit.AuditAction
import bb.produce.invoicing.domain.audit.OutboxEventType
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.invoice.DraftId
import bb.produce.invoicing.domain.invoice.DraftValidator
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.invoice.InvoiceIssuer
import bb.produce.invoicing.domain.invoice.IssueContext
import bb.produce.invoicing.domain.invoice.IssueError
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.invoice.RegistrationError
import bb.produce.invoicing.domain.invoice.ServerRegistration
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.invoice.VoidError
import bb.produce.invoicing.domain.payment.PaymentError
import bb.produce.invoicing.domain.payment.PaymentEvent
import bb.produce.invoicing.domain.payment.PaymentEventKind
import bb.produce.invoicing.domain.payment.PaymentLedger
import bb.produce.invoicing.domain.stock.NegativeStockPolicy
import bb.produce.invoicing.domain.stock.SaleDebitError
import bb.produce.invoicing.domain.stock.SaleDebits
import bb.produce.invoicing.domain.stock.StockError
import bb.produce.invoicing.domain.stock.StockLedger
import bb.produce.invoicing.domain.stock.StockMovement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Points inside the issue transaction, in write order. Used by tests to inject failures. */
enum class IssueStep { INVOICE_ROW, INVOICE_LINES, LEASE_ADVANCED, STOCK_DEBITS, AUDIT, OUTBOX, DRAFT_REMOVED }

sealed interface IssueRejection {
    data object DraftNotFound : IssueRejection
    data object BusinessNotFound : IssueRejection
    /** This driver has no issuing lease on this device (provisioning, Task 04). */
    data object NoLease : IssueRejection
    /** Issuing requires the driver's OPEN shift on this device (stock debit target). */
    data object NoOpenShift : IssueRejection
    data class Domain(val error: IssueError) : IssueRejection
    data class MissingConversion(val error: SaleDebitError.MissingConversion) : IssueRejection
    data class Stock(val error: StockError) : IssueRejection
}

sealed interface IssueResult {
    /** Committed: invoice, lines, lease advance, stock debits, audit and outbox — all or nothing. */
    data class Issued(val invoice: IssuedInvoice, val saleDebits: List<StockMovement>) : IssueResult
    /** This draft was already issued (e.g. a double tap). The existing invoice is returned; nothing new is written. */
    data class AlreadyIssued(val invoice: IssuedInvoice) : IssueResult
    /** Nothing was written. */
    data class Rejected(val reason: IssueRejection) : IssueResult
}

/** Thrown inside the transaction to force a rollback; never escapes [InvoicingRepository.issue]. */
private class RollbackSignal(val result: IssueResult) : RuntimeException(null, null, false, false)

/**
 * Local system of record while offline. Every financial change writes its audit entry and outbox
 * event(s) in the SAME Room transaction (CLAUDE.md invariants 1, 4, 11).
 *
 * @param clock device clock. Informational for timestamps only; never used for identity or ordering.
 * @param afterStep test seam: called after each write in [issue]; throwing from it must roll everything back.
 */
class InvoicingRepository(
    private val db: AppDatabase,
    private val clock: () -> Instant = Instant::now,
    private val afterStep: (IssueStep) -> Unit = {},
) {
    private fun now(): Instant = clock().truncatedTo(ChronoUnit.MILLIS)

    /** This installation's permanent device id; created once. */
    suspend fun deviceId(): DeviceId {
        val dao = db.deviceDao()
        dao.get()?.let { return DeviceId(UUID.fromString(it.deviceId)) }
        dao.insertIfAbsent(DeviceIdentityEntity(deviceId = UUID.randomUUID().toString(), createdAt = now().millis()))
        return DeviceId(UUID.fromString(requireNotNull(dao.get()).deviceId))
    }

    // ------------------------------------------------------------------ drafts

    suspend fun newDraft(businessId: BusinessId, driver: UserId): InvoiceDraft {
        val business = requireNotNull(db.businessDao().get(businessId.toString())) { "Unknown business" }
        val draft = InvoiceDraft.start(businessId, driver, bb.produce.invoicing.domain.catalogue.CatalogueVersion(business.catalogueVersion), now())
        saveDraft(draft)
        return draft
    }

    enum class DraftSaveError { ALREADY_ISSUED }

    /** Auto-save: replaces the draft and its lines atomically. Refuses to resurrect an issued draft. */
    suspend fun saveDraft(draft: InvoiceDraft): Outcome<Unit, DraftSaveError> = db.withTransaction {
        if (db.invoiceDao().bySourceDraft(draft.id.toString()) != null) return@withTransaction Outcome.Err(DraftSaveError.ALREADY_ISSUED)
        val (row, lines) = draft.toEntities()
        db.draftDao().upsert(row)
        db.draftDao().deleteLines(row.id)
        if (lines.isNotEmpty()) db.draftDao().insertLines(lines)
        Outcome.Ok(Unit)
    }

    suspend fun loadDraft(id: DraftId): InvoiceDraft? {
        val row = db.draftDao().get(id.toString()) ?: return null
        return toDraft(row, db.draftDao().lines(row.id))
    }

    suspend fun discardDraft(id: DraftId): Boolean = db.draftDao().delete(id.toString()) > 0

    // ------------------------------------------------------------------ issue

    /**
     * Issues a draft in ONE Room transaction (docs/11 "Local issuance transaction"):
     * invoice + lines, compare-and-set advance of the driver's durable sequence, SALE_DEBIT stock
     * movements, audit entry, outbox events and removal of the draft. Any failure rolls back all of it.
     * Idempotent per draft: a second call returns [IssueResult.AlreadyIssued] (N18).
     */
    suspend fun issue(draftId: DraftId, actor: Actor, invoiceId: InvoiceId = InvoiceId.random()): IssueResult = try {
        db.withTransaction { issueInTransaction(draftId, actor, invoiceId) }
    } catch (signal: RollbackSignal) {
        signal.result
    }

    private suspend fun issueInTransaction(draftId: DraftId, actor: Actor, invoiceId: InvoiceId): IssueResult {
        fun reject(r: IssueRejection): Nothing = throw RollbackSignal(IssueResult.Rejected(r))

        db.invoiceDao().bySourceDraft(draftId.toString())?.let { existing ->
            return IssueResult.AlreadyIssued(toIssuedInvoice(existing, db.invoiceDao().lines(existing.id)))
        }
        val draftRow = db.draftDao().get(draftId.toString()) ?: reject(IssueRejection.DraftNotFound)
        val draft = toDraft(draftRow, db.draftDao().lines(draftRow.id))
        val businessRow = db.businessDao().get(draftRow.businessId) ?: reject(IssueRejection.BusinessNotFound)
        val business = businessRow.toDomain()
        val leaseRow = db.leaseDao().forDriver(businessRow.id, actor.userId.toString()) ?: reject(IssueRejection.NoLease)
        val lease = leaseRow.toDomain()

        val productIds = draft.lines.mapNotNull { it.productId?.toString() }.distinct()
        val prices = if (productIds.isEmpty()) emptyList() else db.catalogueDao().prices(productIds)
        val products = (if (productIds.isEmpty()) emptyList() else db.catalogueDao().products(productIds))
            .map { p -> toProduct(p, prices.filter { it.productId == p.id }) }
            .associateBy { it.id }

        val customer = draft.customerId?.let { db.catalogueDao().customer(it.toString())?.toDomain() }
            ?: reject(IssueRejection.Domain(IssueError.Invalid(DraftValidator.problems(draft, products, false))))

        val shift = db.stockDao().openShift(actor.userId.toString(), actor.deviceId.toString()) ?: reject(IssueRejection.NoOpenShift)
        val at = now()

        val issuance = when (
            val r = InvoiceIssuer.issue(
                draft,
                IssueContext(
                    invoiceId = invoiceId,
                    business = business.snapshot(),
                    customer = customer.snapshot(),
                    driver = leaseRow.driverSnapshot(),
                    actor = actor,
                    issuedAtDevice = at,
                    catalogueVersion = bb.produce.invoicing.domain.catalogue.CatalogueVersion(businessRow.catalogueVersion),
                    products = products,
                    tax = bb.produce.invoicing.domain.money.TaxSnapshot.NotApplied(business.taxConfigVersion),
                ),
                lease,
            )
        ) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> reject(IssueRejection.Domain(r.error))
        }
        val invoice = issuance.invoice

        // Stock: convert to base units and validate against the vehicle ledger before writing anything.
        val conversions = ConversionTable(db.stockDao().conversions(productIds.ifEmpty { listOf("") }).map { it.toDomain() })
        val location = LocationId(UUID.fromString(shift.vehicleLocationId))
        val debits = when (val d = SaleDebits.forInvoice(invoice, conversions, ShiftId(UUID.fromString(shift.id)), location, actor)) {
            is Outcome.Ok -> d.value
            is Outcome.Err -> reject(IssueRejection.MissingConversion(d.error as SaleDebitError.MissingConversion))
        }
        val policy = NegativeStockPolicy.valueOf(businessRow.negativeStockPolicy)
        var ledger = StockLedger.empty(location).let { empty ->
            db.stockDao().movements(location.toString()).fold(empty) { l, m -> l.append(m.toDomain(), NegativeStockPolicy.ALLOW_AND_FLAG).getOrThrow() }
        }
        for (m in debits) {
            ledger = when (val a = ledger.append(m, policy)) {
                is Outcome.Ok -> a.value
                is Outcome.Err -> reject(IssueRejection.Stock(a.error))
            }
        }

        // ---- writes (all inside this transaction) ----
        val (invoiceRow, lineRows) = invoice.toEntities(SyncState.PENDING)
        db.invoiceDao().insert(invoiceRow); afterStep(IssueStep.INVOICE_ROW)
        db.invoiceDao().insertLines(lineRows); afterStep(IssueStep.INVOICE_LINES)

        val advanced = db.leaseDao().advance(
            businessId = businessRow.id,
            driverCode = leaseRow.driverCode,
            epoch = lease.epoch,
            expectedNext = invoice.number.sequence.value,
            newNext = issuance.updatedLease.nextSequence?.value,
            at = at.millis(),
        )
        check(advanced == 1) { "Driver lease changed during issue; rolling back" }
        afterStep(IssueStep.LEASE_ADVANCED)

        if (debits.isNotEmpty()) db.stockDao().insertMovements(debits.map { it.toEntity(business.id) })
        afterStep(IssueStep.STOCK_DEBITS)

        audit(business.id, actor, AuditAction.INVOICE_ISSUED, "invoice", invoice.id.value, at,
            mapOf("number" to invoice.number.text, "lines" to invoice.lines.size.toString(), "saleDebits" to debits.size.toString(),
                "leaseEpoch" to lease.epoch.toString(), "totalMinor" to invoice.total.minor.toString()))
        afterStep(IssueStep.AUDIT)

        db.outboxDao().insertAll(
            listOf(outbox(business.id, OutboxEventType.INVOICE_ISSUED, invoice.id.value, at)) +
                debits.map { outbox(business.id, OutboxEventType.STOCK_MOVEMENT, it.id.value, at) },
        )
        afterStep(IssueStep.OUTBOX)

        check(db.draftDao().delete(draftId.toString()) == 1) { "Draft vanished during issue" }
        afterStep(IssueStep.DRAFT_REMOVED)

        return IssueResult.Issued(invoice, debits)
    }

    // ------------------------------------------------------------------ reads

    suspend fun invoice(id: InvoiceId): IssuedInvoice? {
        val row = db.invoiceDao().get(id.toString()) ?: return null
        return toIssuedInvoice(row, db.invoiceDao().lines(row.id))
    }

    suspend fun invoiceByNumber(businessId: BusinessId, number: String): IssuedInvoice? {
        val row = db.invoiceDao().byNumber(businessId.toString(), number) ?: return null
        return toIssuedInvoice(row, db.invoiceDao().lines(row.id))
    }

    suspend fun syncState(id: InvoiceId): SyncState? = db.invoiceDao().get(id.toString())?.let { SyncState.valueOf(it.syncState) }

    // ------------------------------------------------------------------ registration (server ACK)

    sealed interface RegistrationRejection {
        data object UnknownInvoice : RegistrationRejection
        data class Domain(val error: RegistrationError) : RegistrationRejection
    }

    /**
     * Applies a validated server acknowledgement: sets the internal register reference `PRE-N` once.
     * An identical replay is a no-op; a different reference is rejected and nothing changes.
     */
    suspend fun recordRegistration(reg: ServerRegistration, actor: Actor): Outcome<IssuedInvoice, RegistrationRejection> = db.withTransaction {
        val current = invoice(reg.invoiceId) ?: return@withTransaction Outcome.Err(RegistrationRejection.UnknownInvoice)
        val updated = when (val r = current.withRegistration(reg)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return@withTransaction Outcome.Err(RegistrationRejection.Domain(r.error))
        }
        if (current.registration == null) {
            val n = db.invoiceDao().setRegistration(
                reg.invoiceId.toString(), reg.registerReference.businessCode.value, reg.registerReference.sequence.value,
                reg.firstReceivedAtServer.millis(), reg.registeredAtServer.millis(),
            )
            check(n == 1) { "Registration write failed" }
            audit(current.business.businessId, actor, AuditAction.INVOICE_REGISTERED, "invoice", current.id.value, now(),
                mapOf("number" to current.number.text, "registerReference" to reg.registerReference.text))
        }
        Outcome.Ok(updated)
    }

    // ------------------------------------------------------------------ void

    suspend fun void(id: InvoiceId, actor: Actor, reason: String): Outcome<IssuedInvoice, VoidError?> = db.withTransaction {
        val current = invoice(id) ?: return@withTransaction Outcome.Err(null)
        val at = now()
        val voided = when (val r = current.void(actor, reason, at)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return@withTransaction Outcome.Err(r.error)
        }
        val v = requireNotNull(voided.voidRecord)
        check(db.invoiceDao().setVoid(id.toString(), v.reason, v.by.toString(), v.deviceId.toString(), v.at.millis()) == 1)
        audit(current.business.businessId, actor, AuditAction.INVOICE_VOIDED, "invoice", id.value, at, mapOf("number" to current.number.text))
        db.outboxDao().insert(outbox(current.business.businessId, OutboxEventType.INVOICE_VOIDED, id.value, at))
        Outcome.Ok(voided)
    }

    // ------------------------------------------------------------------ payments

    suspend fun paymentLedger(id: InvoiceId): PaymentLedger? {
        val inv = invoice(id) ?: return null
        return PaymentLedger.forInvoice(inv, db.paymentDao().forInvoice(id.toString()).map { it.toDomain() })
    }

    /** Appends a payment event with its audit + outbox entries. Re-recording the identical event is a no-op. */
    suspend fun recordPayment(event: PaymentEvent, actor: Actor): Outcome<PaymentLedger, PaymentError?> = db.withTransaction {
        val ledger = paymentLedger(event.invoiceId) ?: return@withTransaction Outcome.Err(null)
        if (ledger.events.any { it.id == event.id }) {
            return@withTransaction ledger.record(event).let { if (it is Outcome.Ok) Outcome.Ok(it.value) else Outcome.Err((it as Outcome.Err).error) }
        }
        val next = when (val r = ledger.record(event)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return@withTransaction Outcome.Err(r.error)
        }
        val inv = requireNotNull(invoice(event.invoiceId))
        db.paymentDao().insert(event.toEntity())
        val action = if (event.kind == PaymentEventKind.MARKED_PAID) AuditAction.PAYMENT_MARKED_PAID else AuditAction.PAYMENT_REVERSED
        audit(inv.business.businessId, actor, action, "payment_event", event.id.value, now(),
            mapOf("invoice" to inv.number.text, "method" to event.method.name, "amountMinor" to event.amount.minor.toString()))
        db.outboxDao().insert(outbox(inv.business.businessId, OutboxEventType.PAYMENT_EVENT, event.id.value, now()))
        Outcome.Ok(next)
    }

    data class StorageSummary(val schemaVersion: Int, val invoices: Int, val drafts: Int, val pendingOutbox: Int)

    /** Facts for the diagnostics screen; reads only. */
    suspend fun storageSummary() = StorageSummary(
        AppDatabase.VERSION, db.invoiceDao().count(), db.draftDao().count(), db.outboxDao().pendingCount(),
    )

    // ------------------------------------------------------------------ outbox

    suspend fun pendingOutbox(limit: Int = 100) = db.outboxDao().pending(limit)

    /** Idempotent: a repeated ACK leaves the first acknowledgement time. */
    suspend fun acknowledgeOutbox(eventId: EventId): Boolean = db.outboxDao().acknowledge(eventId.toString(), now().millis()) == 1

    // ------------------------------------------------------------------ helpers

    private suspend fun audit(business: BusinessId, actor: Actor, action: AuditAction, type: String, entity: UUID, at: Instant, details: Map<String, String>) {
        // Validate through the domain model (rejects PII/secret keys) before writing.
        val e = bb.produce.invoicing.domain.audit.AuditEvent(EventId.random(), business, actor.userId, actor.deviceId, action, type, entity, at, details)
        db.auditDao().insert(
            AuditEventEntity(
                e.id.toString(), business.toString(), actor.userId.toString(), actor.deviceId.toString(), action.name, type,
                entity.toString(), at.millis(), details.entries.joinToString("\n") { "${it.key}=${it.value}" },
            ),
        )
    }

    private fun outbox(business: BusinessId, type: OutboxEventType, aggregate: UUID, at: Instant) = OutboxEventEntity(
        eventId = EventId.random().toString(), businessId = business.toString(), type = type.name, aggregateId = aggregate.toString(),
        payloadSchemaVersion = 1, createdAt = at.millis(), attempts = 0, nextAttemptAt = null, ackedAt = null,
    )
}
