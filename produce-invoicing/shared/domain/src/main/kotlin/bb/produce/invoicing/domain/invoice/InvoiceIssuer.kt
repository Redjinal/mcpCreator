package bb.produce.invoicing.domain.invoice

import bb.produce.invoicing.domain.catalogue.BusinessSnapshot
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.CustomerSnapshot
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.identity.CustomerInvoiceNumber
import bb.produce.invoicing.domain.identity.DriverSequenceLease
import bb.produce.invoicing.domain.identity.DriverSnapshot
import bb.produce.invoicing.domain.identity.LeaseError
import bb.produce.invoicing.domain.money.LineMath
import bb.produce.invoicing.domain.money.RoundingPolicy
import bb.produce.invoicing.domain.money.TaxSnapshot
import java.time.Instant

/** Everything frozen into the invoice at issue, supplied by the caller from local data. */
data class IssueContext(
    val invoiceId: InvoiceId,
    val business: BusinessSnapshot,
    val customer: CustomerSnapshot,
    val driver: DriverSnapshot,
    val actor: Actor,
    val issuedAtDevice: Instant,
    val catalogueVersion: CatalogueVersion,
    val products: Map<ProductId, Product>,
    val tax: TaxSnapshot,
    /** D06: drivers may not override catalogue prices unless authorised. */
    val priceOverrideAllowed: Boolean = false,
)

sealed interface IssueError {
    data class Invalid(val problems: List<DraftProblem>) : IssueError
    data class Lease(val error: LeaseError) : IssueError
    /** Context customer is not the draft's customer. */
    data object CustomerMismatch : IssueError
    /** Only the driver whose code is on the lease may issue, from their own session. */
    data object ActorNotDriver : IssueError
    data object BusinessMismatch : IssueError
}

/** Result of a successful issue: the invoice AND the advanced lease, to be persisted in ONE transaction. */
data class Issuance(val invoice: IssuedInvoice, val updatedLease: DriverSequenceLease)

/**
 * Pure issue operation (docs/11 "Local issuance transaction" step 2). It allocates the next
 * per-driver sequence from the fenced lease and freezes all snapshots. It has no side effects:
 * the caller (Task 02) persists [Issuance.invoice], [Issuance.updatedLease], SALE_DEBIT movements,
 * audit and outbox in one Room transaction, or nothing at all.
 */
object InvoiceIssuer {

    fun issue(draft: InvoiceDraft, ctx: IssueContext, lease: DriverSequenceLease): Outcome<Issuance, IssueError> {
        if (draft.businessId != ctx.business.businessId) return Outcome.Err(IssueError.BusinessMismatch)
        if (ctx.actor.userId != ctx.driver.userId || draft.driverUserId != ctx.driver.userId) {
            return Outcome.Err(IssueError.ActorNotDriver)
        }
        if (draft.customerId != null && draft.customerId != ctx.customer.customerId) return Outcome.Err(IssueError.CustomerMismatch)

        val problems = DraftValidator.problems(draft, ctx.products, ctx.priceOverrideAllowed)
        if (problems.isNotEmpty()) return Outcome.Err(IssueError.Invalid(problems))

        val allocation = when (val a = lease.allocate(ctx.driver.driverCode, ctx.actor.deviceId, ctx.issuedAtDevice)) {
            is Outcome.Ok -> a.value
            is Outcome.Err -> return Outcome.Err(IssueError.Lease(a.error))
        }

        val lines = draft.lines.mapIndexed { i, l ->
            val qty = requireNotNull(l.quantity)
            val price = requireNotNull(l.unitPrice)
            IssuedLine(
                lineId = l.id,
                position = i + 1,
                productId = l.productId,
                description = l.description,
                unit = l.unit,
                quantity = qty,
                unitPrice = price,
                lineTotal = LineMath.lineTotal(qty, price),
                catalogueUnitPrice = l.catalogueUnitPrice,
                zeroPriceConfirmed = l.zeroPriceConfirmed,
            )
        }

        val invoice = IssuedInvoice.create(
            id = ctx.invoiceId,
            sourceDraftId = draft.id,
            number = CustomerInvoiceNumber(ctx.business.code, allocation.sequence, ctx.driver.driverCode),
            business = ctx.business,
            customer = ctx.customer,
            driver = ctx.driver,
            issuedBy = ctx.actor.userId,
            deviceId = ctx.actor.deviceId,
            leaseEpoch = lease.epoch,
            catalogueVersion = ctx.catalogueVersion,
            roundingPolicyId = RoundingPolicy.id,
            tax = ctx.tax,
            lines = lines,
            issuedAtDevice = ctx.issuedAtDevice,
        )
        return Outcome.Ok(Issuance(invoice, allocation.updatedLease))
    }
}
