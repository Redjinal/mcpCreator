package bb.produce.invoicing.domain.audit

import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.UserId
import java.time.Instant
import java.util.UUID

enum class AuditAction {
    INVOICE_ISSUED, INVOICE_VOIDED, INVOICE_REGISTERED,
    PRINT_ATTEMPTED, PRINT_OUTCOME, REPRINT,
    PAYMENT_MARKED_PAID, PAYMENT_REVERSED,
    STOCK_MOVEMENT_RECORDED, RETURN_DISPOSITION,
    SHIFT_TRANSITION,
    CATALOGUE_CHANGED, PRICE_PUBLISHED, ROLE_CHANGED,
    LEASE_GRANTED, LEASE_REVOKED, LEASE_CONFLICT,
    SYNC_SUCCEEDED, SYNC_FAILED,
}

/**
 * Append-only audit entry. `details` carries identifiers and codes only — no customer PII, tokens or
 * secrets (docs/05 "minimal logs").
 */
data class AuditEvent(
    val id: EventId,
    val businessId: BusinessId,
    val actorId: UserId,
    val deviceId: DeviceId,
    val action: AuditAction,
    val entityType: String,
    val entityId: UUID,
    val at: Instant,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(entityType.isNotBlank()) { "entityType required" }
        require(details.keys.none { it.lowercase() in FORBIDDEN_KEYS }) { "Audit details must not carry secrets/PII" }
    }

    companion object {
        private val FORBIDDEN_KEYS = setOf("password", "pin", "token", "secret", "card", "cardnumber", "phone", "address", "email")
    }
}

enum class OutboxEventType {
    INVOICE_ISSUED, INVOICE_VOIDED, PAYMENT_EVENT, STOCK_MOVEMENT, RETURN_INTAKE,
    SHIFT_EVENT, STOCK_COUNT, PRINT_ATTEMPT, CUSTOMER_CREATED,
}

/**
 * Transactional outbox row: written in the SAME local transaction as the change it describes.
 * [eventId] is the upload idempotency key; the server dedupes on it (docs/05).
 */
data class OutboxEvent(
    val eventId: EventId,
    val businessId: BusinessId,
    val type: OutboxEventType,
    val aggregateId: UUID,
    val payloadSchemaVersion: Int,
    val createdAt: Instant,
    val attempts: Int = 0,
    val nextAttemptAt: Instant? = null,
    val ackedAt: Instant? = null,
) {
    init {
        require(payloadSchemaVersion >= 1) { "payload schema version >= 1" }
        require(attempts >= 0) { "attempts >= 0" }
    }

    val isAcked: Boolean get() = ackedAt != null

    fun recordFailedAttempt(nextAttemptAt: Instant): OutboxEvent {
        check(!isAcked) { "Acked events are not retried" }
        return copy(attempts = attempts + 1, nextAttemptAt = nextAttemptAt)
    }

    /** Idempotent: a repeated ACK keeps the first acknowledgement time. */
    fun acknowledge(at: Instant): OutboxEvent = if (isAcked) this else copy(ackedAt = at, nextAttemptAt = null)
}
