package bb.produce.invoicing.domain.invoice

import bb.produce.invoicing.domain.catalogue.BusinessSnapshot
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.CustomerSnapshot
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.identity.CustomerInvoiceNumber
import bb.produce.invoicing.domain.identity.DriverSnapshot
import bb.produce.invoicing.domain.identity.RegisterReference
import bb.produce.invoicing.domain.money.LineMath
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.TaxSnapshot
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import java.time.Duration
import java.time.Instant
import java.util.Collections

/** Invoice lifecycle. Print and sync status are orthogonal and tracked separately (docs/03). */
enum class InvoiceStatus {
    DRAFT, ISSUED, VOIDED;

    fun canTransitionTo(next: InvoiceStatus): Boolean = (this == DRAFT && next == ISSUED) || (this == ISSUED && next == VOIDED)
}

enum class SyncState { PENDING, SYNCING, SYNCED, CONFLICT, FAILED_RETRYABLE }

/** A frozen invoice line. The total is validated against the arithmetic rule on construction. */
data class IssuedLine(
    val lineId: LineId,
    val position: Int,
    val productId: ProductId?,
    val description: String,
    val unit: UnitCode,
    val quantity: Quantity,
    val unitPrice: UnitPrice,
    val lineTotal: Money,
    val catalogueUnitPrice: UnitPrice?,
    val zeroPriceConfirmed: Boolean,
) {
    init {
        require(position >= 1) { "Line position starts at 1" }
        require(description.isNotBlank()) { "Line description required" }
        require(lineTotal == LineMath.lineTotal(quantity, unitPrice)) { "Line total does not match quantity × price" }
        require(!unitPrice.isZero || zeroPriceConfirmed) { "Zero price must be confirmed" }
    }

    val priceOverridden: Boolean get() = catalogueUnitPrice != null && catalogueUnitPrice != unitPrice
}

/**
 * Server acknowledgement of first accepted registration (docs/11). Carries the internal register
 * reference `PRE-N` and the server instants. Never generated on the client.
 */
data class ServerRegistration(
    val invoiceId: InvoiceId,
    val customerInvoiceNumber: CustomerInvoiceNumber,
    val registerReference: RegisterReference,
    val firstReceivedAtServer: Instant,
    val registeredAtServer: Instant,
) {
    init {
        require(!registeredAtServer.isBefore(firstReceivedAtServer)) { "registered_at precedes first_received_at" }
    }
}

data class VoidRecord(val reason: String, val by: UserId, val deviceId: DeviceId, val at: Instant)

enum class RegistrationError { WRONG_INVOICE, NUMBER_MISMATCH, BUSINESS_MISMATCH, CONFLICTING_REGISTER_REFERENCE }
enum class VoidError { NOT_AUTHORIZED, REASON_REQUIRED, ALREADY_VOIDED }

/**
 * An issued invoice: an immutable financial snapshot. There is no public constructor or `copy`;
 * instances come only from [InvoiceIssuer]. The only state changes are:
 *  - attaching the server registration (once; identical replays are idempotent), and
 *  - voiding (owner only, with reason) — which keeps the number reserved and all values intact.
 * The customer-facing number never changes after issue.
 */
@ConsistentCopyVisibility
data class IssuedInvoice private constructor(
    val id: InvoiceId,
    val sourceDraftId: DraftId,
    val number: CustomerInvoiceNumber,
    val business: BusinessSnapshot,
    val customer: CustomerSnapshot,
    val driver: DriverSnapshot,
    val issuedBy: UserId,
    val deviceId: DeviceId,
    val leaseEpoch: Long,
    val catalogueVersion: CatalogueVersion,
    val roundingPolicyId: String,
    val tax: TaxSnapshot,
    val lines: List<IssuedLine>,
    val subtotal: Money,
    val taxTotal: Money,
    val total: Money,
    /** Device-reported issue time (UTC). Informational; never used for identity or register ordering. */
    val issuedAtDevice: Instant,
    val registration: ServerRegistration?,
    val voidRecord: VoidRecord?,
) {
    val status: InvoiceStatus get() = if (voidRecord == null) InvoiceStatus.ISSUED else InvoiceStatus.VOIDED
    val registerReference: RegisterReference? get() = registration?.registerReference
    val firstReceivedAtServer: Instant? get() = registration?.firstReceivedAtServer
    val registeredAtServer: Instant? get() = registration?.registeredAtServer

    /**
     * Device clock looks ahead of the server: the device claims to have issued *after* the server first
     * received it. (A device clock that is behind is indistinguishable from a late sync, so only a
     * flag is raised; ordering never depends on it.)
     */
    fun deviceClockAheadOfServer(tolerance: Duration = Duration.ofMinutes(5)): Boolean {
        val received = firstReceivedAtServer ?: return false
        return issuedAtDevice.isAfter(received.plus(tolerance))
    }

    fun withRegistration(reg: ServerRegistration): Outcome<IssuedInvoice, RegistrationError> = when {
        reg.invoiceId != id -> Outcome.Err(RegistrationError.WRONG_INVOICE)
        reg.customerInvoiceNumber != number -> Outcome.Err(RegistrationError.NUMBER_MISMATCH)
        reg.registerReference.businessCode != number.businessCode -> Outcome.Err(RegistrationError.BUSINESS_MISMATCH)
        registration == null -> Outcome.Ok(copy(registration = reg))
        registration == reg -> Outcome.Ok(this)
        else -> Outcome.Err(RegistrationError.CONFLICTING_REGISTER_REFERENCE)
    }

    fun void(actor: Actor, reason: String, at: Instant): Outcome<IssuedInvoice, VoidError> = when {
        !actor.isOwner -> Outcome.Err(VoidError.NOT_AUTHORIZED)
        reason.isBlank() -> Outcome.Err(VoidError.REASON_REQUIRED)
        voidRecord != null -> Outcome.Err(VoidError.ALREADY_VOIDED)
        else -> Outcome.Ok(copy(voidRecord = VoidRecord(reason.trim(), actor.userId, actor.deviceId, at)))
    }

    internal companion object {
        fun create(
            id: InvoiceId,
            sourceDraftId: DraftId,
            number: CustomerInvoiceNumber,
            business: BusinessSnapshot,
            customer: CustomerSnapshot,
            driver: DriverSnapshot,
            issuedBy: UserId,
            deviceId: DeviceId,
            leaseEpoch: Long,
            catalogueVersion: CatalogueVersion,
            roundingPolicyId: String,
            tax: TaxSnapshot,
            lines: List<IssuedLine>,
            issuedAtDevice: Instant,
        ): IssuedInvoice {
            require(lines.isNotEmpty()) { "Issued invoice needs lines" }
            require(lines.map { it.position } == (1..lines.size).toList()) { "Line positions must be 1..n" }
            require(number.businessCode == business.code) { "Number prefix must match business code" }
            require(number.driverCode == driver.driverCode) { "Number suffix must match driver code" }
            val subtotal = Money.sum(lines.map { it.lineTotal })
            val taxTotal = tax.taxOn(subtotal)
            return IssuedInvoice(
                id, sourceDraftId, number, business, customer, driver, issuedBy, deviceId, leaseEpoch,
                catalogueVersion, roundingPolicyId, tax,
                Collections.unmodifiableList(ArrayList(lines)),
                subtotal, taxTotal, subtotal + taxTotal, issuedAtDevice, null, null,
            )
        }
    }
}
