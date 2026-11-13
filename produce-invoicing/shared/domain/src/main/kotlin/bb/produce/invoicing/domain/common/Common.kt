package bb.produce.invoicing.domain.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Result of a validation that a user can act on. Programming errors use exceptions instead. */
sealed interface Outcome<out T, out E> {
    data class Ok<T>(val value: T) : Outcome<T, Nothing>
    data class Err<E>(val error: E) : Outcome<Nothing, E>

    val isOk: Boolean get() = this is Ok

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw IllegalStateException("Expected Ok but was Err($error)")
    }

    fun errorOrNull(): E? = (this as? Err)?.error
}

// ---- Stable identifiers (UUIDs). Never derived from numbers, names or timestamps. ----

@JvmInline value class BusinessId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class UserId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class DeviceId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class CustomerId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ProductId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ShiftId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class LocationId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class PrinterProfileId(val value: UUID) { override fun toString() = value.toString() }

/** Permanent invoice identity, generated at issue (or at draft creation) and never reused. */
@JvmInline value class InvoiceId(val value: UUID) {
    override fun toString() = value.toString()
    companion object { fun random() = InvoiceId(UUID.randomUUID()) }
}

@JvmInline value class LineId(val value: UUID) {
    override fun toString() = value.toString()
    companion object { fun random() = LineId(UUID.randomUUID()) }
}

/** Identity of any append-only event (audit, outbox, payment, stock movement). Used for idempotency. */
@JvmInline value class EventId(val value: UUID) {
    override fun toString() = value.toString()
    companion object { fun random() = EventId(UUID.randomUUID()) }
}

enum class Role { OWNER, DRIVER }

/** Who did something, from which device. Permission checks in the domain are repeated in repositories/API. */
data class Actor(val userId: UserId, val role: Role, val deviceId: DeviceId) {
    val isOwner: Boolean get() = role == Role.OWNER
}

/**
 * All instants are stored in UTC; Barbados local time is for display only.
 * The device clock is informational and never used for identity or register ordering (docs/11).
 */
object BarbadosTime {
    val ZONE: ZoneId = ZoneId.of("America/Barbados")
    private val DISPLAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK).withZone(ZONE)

    /** e.g. `16 Oct 2026, 09:05 AST` (Barbados has no DST). */
    fun display(instant: Instant): String = DISPLAY.format(instant) + " AST"

    fun localDate(instant: Instant) = instant.atZone(ZONE).toLocalDate()
}
