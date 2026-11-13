package bb.produce.invoicing.domain.identity

import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.UserId
import java.time.Instant

/*
 * V3 approved numbering (docs/11, binding):
 *   customer-facing  PRE-INV-SUF   e.g. BGI-100-AB  — issued offline from a per-driver sequence, immutable
 *   internal register PRE-N        e.g. BGI-101     — assigned by the office server at first accepted registration
 * Uniqueness is scoped by business UUID, never by the readable abbreviation alone.
 * There are NO globally partitioned number blocks.
 */

/** `PRE`: business abbreviation, normalised upper case, 2–8 of [A-Z0-9]. No hyphens (keeps parsing unambiguous). */
@JvmInline
value class BusinessCode private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private val PATTERN = Regex("[A-Z0-9]{2,8}")
        fun parse(raw: String?): BusinessCode? = raw?.trim()?.uppercase()?.takeIf(PATTERN::matches)?.let(::BusinessCode)
        fun of(raw: String): BusinessCode = requireNotNull(parse(raw)) { "Invalid business code '$raw' (2–8 of A–Z, 0–9)" }
    }
}

/** `SUF`: permanent driver identifier, 1–8 of [A-Z0-9]. Never reassigned to another person within a business. */
@JvmInline
value class DriverCode private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private val PATTERN = Regex("[A-Z0-9]{1,8}")
        fun parse(raw: String?): DriverCode? = raw?.trim()?.uppercase()?.takeIf(PATTERN::matches)?.let(::DriverCode)
        fun of(raw: String): DriverCode = requireNotNull(parse(raw)) { "Invalid driver code '$raw' (1–8 of A–Z, 0–9)" }
    }
}

/**
 * `INV`: the driver's own running invoice number. Positive and bounded; overflow is an explicit
 * error ([next] returns null) rather than wrapping or resetting.
 */
@JvmInline
value class DriverSequence(val value: Long) : Comparable<DriverSequence> {
    init {
        require(value in 1..MAX) { "Driver sequence must be in 1..$MAX but was $value" }
    }

    /** The next number, or null when the sequence is exhausted (needs an owner decision, e.g. new driver code). */
    fun next(): DriverSequence? = if (value == MAX) null else DriverSequence(value + 1)

    override fun compareTo(other: DriverSequence) = value.compareTo(other.value)
    override fun toString() = value.toString()

    companion object {
        /** 9 digits. Configurable after client review. */
        const val MAX: Long = 999_999_999L
    }
}

/** The printed, customer-facing invoice number `PRE-INV-SUF`. Derived from its parts; never edited. */
data class CustomerInvoiceNumber(
    val businessCode: BusinessCode,
    val sequence: DriverSequence,
    val driverCode: DriverCode,
) {
    val text: String get() = "${businessCode.value}-${sequence.value}-${driverCode.value}"
    override fun toString() = text

    companion object {
        /** Strict parse of canonical text. Rejects leading zeros so `BGI-0100-AB` cannot alias `BGI-100-AB`. */
        fun parse(text: String?): CustomerInvoiceNumber? {
            val parts = text?.trim()?.split('-') ?: return null
            if (parts.size != 3) return null
            val (pre, inv, suf) = parts
            if (pre != pre.uppercase() || suf != suf.uppercase()) return null
            val business = BusinessCode.parse(pre) ?: return null
            val driver = DriverCode.parse(suf) ?: return null
            val seq = parseCanonicalPositive(inv, DriverSequence.MAX) ?: return null
            return CustomerInvoiceNumber(business, DriverSequence(seq), driver)
        }
    }
}

/** Server-owned business register counter value `N`. */
@JvmInline
value class RegisterSequence(val value: Long) : Comparable<RegisterSequence> {
    init {
        require(value in 1..MAX) { "Register sequence must be in 1..$MAX but was $value" }
    }

    override fun compareTo(other: RegisterSequence) = value.compareTo(other.value)
    override fun toString() = value.toString()

    companion object { const val MAX: Long = 999_999_999_999L }
}

/**
 * Internal office register reference `PRE-N` (e.g. `BGI-101`). An internal tracking reference —
 * NOT a second invoice number. Only ever created from a validated server acknowledgement.
 */
data class RegisterReference(val businessCode: BusinessCode, val sequence: RegisterSequence) {
    val text: String get() = "${businessCode.value}-${sequence.value}"
    override fun toString() = text

    companion object {
        fun parse(text: String?): RegisterReference? {
            val parts = text?.trim()?.split('-') ?: return null
            if (parts.size != 2) return null
            val (pre, n) = parts
            if (pre != pre.uppercase()) return null
            val business = BusinessCode.parse(pre) ?: return null
            val seq = parseCanonicalPositive(n, RegisterSequence.MAX) ?: return null
            return RegisterReference(business, RegisterSequence(seq))
        }
    }
}

private fun parseCanonicalPositive(text: String, max: Long): Long? {
    if (text.isEmpty() || text.length > 19 || !text.all { it in '0'..'9' } || text.startsWith('0')) return null
    return text.toLongOrNull()?.takeIf { it in 1..max }
}

/** Immutable issue-time snapshot of who issued an invoice. Later name changes never touch it (N09). */
data class DriverSnapshot(val userId: UserId, val driverCode: DriverCode, val displayName: String) {
    init {
        require(displayName.isNotBlank()) { "Driver display name required" }
    }
}

// ---------------------------------------------------------------------------------------------
// Fenced single-device issuing lease (docs/11 "Local issuance transaction" step 1, "Device handoff")
// ---------------------------------------------------------------------------------------------

enum class LeaseError {
    /** This device is not the one the lease was granted to. */
    WRONG_DEVICE,
    /** Lease belongs to a different driver code than the one issuing. */
    WRONG_DRIVER,
    /** Owner revoked the lease (learned at last sync). Issuing must stop. */
    REVOKED,
    /** Offline validity window has passed (policy: client decision). */
    EXPIRED,
    /** Driver sequence reached its maximum. */
    SEQUENCE_EXHAUSTED,
}

/**
 * The exclusive right of ONE device to issue numbers for ONE driver code, fenced by a monotonically
 * increasing [epoch]. [nextSequence] is the next unused number for this driver; it survives shifts,
 * restarts and device handoff (the server hands over the continuation point with a higher epoch).
 *
 * This is a pure value. The durable increment happens inside the Room issue transaction (Task 02),
 * which persists the lease returned by [allocate].
 */
data class DriverSequenceLease(
    val driverCode: DriverCode,
    val deviceId: DeviceId,
    val epoch: Long,
    /** Null when the sequence is exhausted. */
    val nextSequence: DriverSequence?,
    val revoked: Boolean = false,
    /** Optional offline validity limit; null = no expiry configured (client policy pending). */
    val validUntil: Instant? = null,
) {
    init {
        require(epoch >= 1) { "Lease epoch must be >= 1" }
    }

    data class Allocation(val sequence: DriverSequence, val updatedLease: DriverSequenceLease)

    fun allocate(driver: DriverCode, device: DeviceId, now: Instant): Outcome<Allocation, LeaseError> {
        if (revoked) return Outcome.Err(LeaseError.REVOKED)
        if (device != deviceId) return Outcome.Err(LeaseError.WRONG_DEVICE)
        if (driver != driverCode) return Outcome.Err(LeaseError.WRONG_DRIVER)
        if (validUntil != null && !now.isBefore(validUntil)) return Outcome.Err(LeaseError.EXPIRED)
        val seq = nextSequence ?: return Outcome.Err(LeaseError.SEQUENCE_EXHAUSTED)
        return Outcome.Ok(Allocation(seq, copy(nextSequence = seq.next())))
    }

    /** Server-side fencing check: a request carrying an older epoch is stale and must be quarantined. */
    fun isStale(requestEpoch: Long): Boolean = requestEpoch < epoch
}
