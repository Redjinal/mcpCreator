package bb.produce.invoicing.domain.invoice

import bb.produce.invoicing.domain.Fx
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.catalogue.ProductUnitPrice
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.identity.CustomerInvoiceNumber
import bb.produce.invoicing.domain.identity.DriverSequence
import bb.produce.invoicing.domain.identity.LeaseError
import bb.produce.invoicing.domain.identity.RegisterReference
import bb.produce.invoicing.domain.identity.RegisterSequence
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class DraftAndIssueTest {

    private fun problems(d: InvoiceDraft, overrideAllowed: Boolean = false) =
        DraftValidator.problems(d, Fx.products, overrideAllowed).map { it.code }

    // ---------------- drafts ----------------

    @Test
    fun `quantity changes recompute line and running totals exactly`() {
        var d = Fx.validDraft()
        assertEquals(Money.ofMinor(5_625 + 75), d.runningTotal) // 12.5 x 4.50 + 3 x 0.25
        d = d.updateQuantity(d.lines[0].id, Quantity.of("0.335"), Fx.T0).getOrThrow()
        assertEquals(Money.ofMinor(151), d.lines[0].lineTotal) // 150.75 -> 151
        assertEquals(Money.ofMinor(151 + 75), d.runningTotal)
        d = d.updateQuantity(d.lines[0].id, null, Fx.T0).getOrThrow()
        assertNull(d.lines[0].lineTotal)
        assertEquals(listOf(DraftProblemCode.MISSING_QUANTITY), problems(d))
    }

    @Test
    fun `adding the same product and unit again increments quantity instead of a duplicate line`() {
        var d = Fx.validDraft()
        d = d.addProduct(Fx.limes, Fx.each, Fx.T0).getOrThrow()
        assertEquals(2, d.lines.size)
        assertEquals(Quantity.of("4"), d.lines[1].quantity)
    }

    @Test
    fun `units not permitted for a product are rejected when adding and changing`() {
        val d = Fx.validDraft()
        assertEquals(Outcome.Err(DraftEditError.UNIT_NOT_ALLOWED_FOR_PRODUCT), d.addProduct(Fx.tomatoes, Fx.case, Fx.T0))
        assertEquals(
            Outcome.Err(DraftEditError.UNIT_NOT_ALLOWED_FOR_PRODUCT),
            d.changeUnit(d.lines[0].id, Fx.each, Fx.tomatoes, Fx.T0),
        )
    }

    @Test
    fun `catalogue removing a unit after the line was added blocks issue`() {
        val d = Fx.validDraft()
        val tomatoesLbOnly = Fx.tomatoes.copy(units = listOf(ProductUnitPrice(Fx.kg, UnitPrice.ofMinor(990))))
        val p = DraftValidator.problems(d, Fx.products + (Fx.tomatoes.id to tomatoesLbOnly), false).map { it.code }
        assertEquals(listOf(DraftProblemCode.UNIT_NOT_ALLOWED), p)
    }

    @Test
    fun `changing unit re-applies the catalogue price and does not convert quantity`() {
        var d = Fx.validDraft()
        d = d.changeUnit(d.lines[0].id, Fx.kg, Fx.tomatoes, Fx.T0).getOrThrow()
        assertEquals(Fx.kg, d.lines[0].unit)
        assertEquals(UnitPrice.ofMinor(990), d.lines[0].unitPrice)
        assertEquals(Quantity.of("12.5"), d.lines[0].quantity)
    }

    @Test
    fun `zero price needs explicit confirmation and editing the price clears it`() {
        var d = Fx.validDraft()
        val id = d.lines[0].id
        d = d.updateUnitPrice(id, UnitPrice.ofMinor(0), Fx.T0).getOrThrow()
        assertTrue(DraftProblemCode.ZERO_PRICE_NOT_CONFIRMED in problems(d, overrideAllowed = true))
        d = d.confirmZeroPrice(id, true, Fx.T0).getOrThrow()
        assertEquals(emptyList<DraftProblemCode>(), problems(d, overrideAllowed = true))
        d = d.updateUnitPrice(id, UnitPrice.ofMinor(0), Fx.T0).getOrThrow()
        assertFalse(d.lines[0].zeroPriceConfirmed)
    }

    @Test
    fun `price override of a catalogue price needs authorisation (D06)`() {
        var d = Fx.validDraft()
        d = d.updateUnitPrice(d.lines[0].id, UnitPrice.ofMinor(400), Fx.T0).getOrThrow()
        assertEquals(listOf(DraftProblemCode.PRICE_OVERRIDE_NOT_AUTHORIZED), problems(d))
        assertEquals(emptyList<DraftProblemCode>(), problems(d, overrideAllowed = true))
    }

    @Test
    fun `price-on-invoice products and custom lines need an entered price and description`() {
        var d = Fx.validDraft().addProduct(Fx.pumpkin, Fx.lb, Fx.T0).getOrThrow()
        d = d.addCustomLine("  ", Fx.each, Fx.T0)
        assertEquals(
            listOf(DraftProblemCode.MISSING_PRICE, DraftProblemCode.MISSING_DESCRIPTION, DraftProblemCode.MISSING_PRICE),
            problems(d),
        )
        d = d.updateUnitPrice(d.lines[2].id, UnitPrice.ofMinor(150), Fx.T0).getOrThrow()
        d = d.updateDescription(d.lines[3].id, "Delivery crate deposit", Fx.T0).getOrThrow()
        d = d.updateUnitPrice(d.lines[3].id, UnitPrice.ofMinor(500), Fx.T0).getOrThrow()
        assertEquals(emptyList<DraftProblemCode>(), problems(d))
        assertEquals(Outcome.Err(DraftEditError.NOT_A_CUSTOM_LINE), d.updateDescription(d.lines[0].id, "x", Fx.T0))
    }

    @Test
    fun `empty draft, missing customer and inactive product are reported`() {
        val empty = InvoiceDraft.start(Fx.businessId, Fx.driverAbUser, Fx.catalogueV1, Fx.T0)
        assertEquals(listOf(DraftProblemCode.NO_CUSTOMER, DraftProblemCode.NO_LINES), problems(empty))
        val inactive = Fx.limes.copy(active = false)
        assertEquals(Outcome.Err(DraftEditError.PRODUCT_INACTIVE), empty.addProduct(inactive, Fx.each, Fx.T0))
        val d = Fx.validDraft()
        val p = DraftValidator.problems(d, Fx.products + (inactive.id to inactive), false).map { it.code }
        assertEquals(listOf(DraftProblemCode.PRODUCT_INACTIVE), p)
    }

    @Test
    fun `removing a line and unknown line ids`() {
        val d = Fx.validDraft()
        val removed = d.removeLine(d.lines[0].id, Fx.T0).getOrThrow()
        assertEquals(1, removed.lines.size)
        assertEquals(Outcome.Err(DraftEditError.UNKNOWN_LINE), removed.removeLine(d.lines[0].id, Fx.T0))
    }

    // ---------------- issue ----------------

    @Test
    fun `issue freezes snapshots, totals, actor, device, catalogue version and the number`() {
        val ctx = Fx.context()
        val (inv, lease) = Fx.issue(ctx = ctx)
        assertEquals("BGI-100-AB", inv.number.text)
        assertEquals(InvoiceStatus.ISSUED, inv.status)
        assertEquals(ctx.invoiceId, inv.id)
        assertEquals(Money.ofMinor(5_700), inv.total)
        assertEquals(Money.ofMinor(5_700), inv.subtotal)
        assertEquals(Money.zero(), inv.taxTotal)
        assertEquals("Test Grocer One", inv.customer.name)
        assertEquals("Synthetic Produce Co", inv.business.name)
        assertEquals("Driver Alpha", inv.driver.displayName)
        assertEquals(Fx.driverAbUser, inv.issuedBy)
        assertEquals(Fx.deviceAb, inv.deviceId)
        assertEquals(Fx.catalogueV1, inv.catalogueVersion)
        assertEquals("LINE_HALF_UP_CENTS_V1", inv.roundingPolicyId)
        assertEquals(Fx.T0, inv.issuedAtDevice)
        assertNull(inv.registerReference)
        assertNull(inv.firstReceivedAtServer)
        assertNull(inv.registeredAtServer)
        assertEquals(listOf(1, 2), inv.lines.map { it.position })
        assertEquals(DriverSequence(101), lease.nextSequence)
    }

    @Test
    fun `issued lines cannot be mutated and there is no public copy`() {
        val inv = Fx.issue().invoice
        @Suppress("UNCHECKED_CAST")
        val asMutable = inv.lines as MutableList<IssuedLine>
        assertThrows<UnsupportedOperationException> { asMutable.clear() }
        assertThrows<UnsupportedOperationException> { asMutable[0] = asMutable[0] }
        // IssuedLine invariants: a total that does not match qty x price cannot be constructed.
        assertThrows<IllegalArgumentException> { inv.lines[0].copy(lineTotal = Money.ofMinor(1)) }
    }

    @Test
    fun `A5 - later catalogue price and customer edits do not change an issued invoice`() {
        val inv = Fx.issue().invoice
        val repriced = Fx.tomatoes.copy(units = listOf(ProductUnitPrice(Fx.lb, UnitPrice.ofMinor(500))))
        val renamedCustomer = Fx.customer.copy(name = "Renamed Grocer")
        // New draft uses the new catalogue version...
        val newDraft = InvoiceDraft.start(Fx.businessId, Fx.driverAbUser, CatalogueVersion(2), Fx.T0)
            .withCustomer(renamedCustomer.id, Fx.T0).addProduct(repriced, Fx.lb, Fx.T0).getOrThrow()
        assertEquals(UnitPrice.ofMinor(500), newDraft.lines[0].unitPrice)
        // ...the issued snapshot keeps the old values.
        assertEquals(UnitPrice.ofMinor(450), inv.lines[0].unitPrice)
        assertEquals("Test Grocer One", inv.customer.name)
        assertEquals(Fx.catalogueV1, inv.catalogueVersion)
    }

    @Test
    fun `N09 - driver rename keeps the issue-time name snapshot`() {
        val inv = Fx.issue().invoice
        val renamed = Fx.driverAb.copy(displayName = "Driver Alpha Renamed")
        val next = Fx.issue(ctx = Fx.context(driver = renamed), lease = Fx.lease(Fx.ab, Fx.deviceAb, next = 101)).invoice
        assertEquals("Driver Alpha", inv.driver.displayName)
        assertEquals("Driver Alpha Renamed", next.driver.displayName)
        assertEquals("BGI-101-AB", next.number.text)
    }

    @Test
    fun `N01 - two drivers each issue 100 offline with distinct UUIDs`() {
        val ab = Fx.issue().invoice
        val fd = Fx.issue(
            draft = Fx.validDraft(driver = Fx.driverFdUser),
            ctx = Fx.context(driver = Fx.driverFd, actor = Fx.actorFd),
            lease = Fx.lease(Fx.fd, Fx.deviceFd),
        ).invoice
        assertEquals("BGI-100-AB", ab.number.text)
        assertEquals("BGI-100-FD", fd.number.text)
        assertNotEquals(ab.id, fd.id)
    }

    @Test
    fun `A2 - 25 invoices each on two offline devices never collide`() {
        val numbers = mutableSetOf<String>()
        for ((driver, actor, startLease) in listOf(
            Triple(Fx.driverAb, Fx.actorAb, Fx.lease(Fx.ab, Fx.deviceAb)),
            Triple(Fx.driverFd, Fx.actorFd, Fx.lease(Fx.fd, Fx.deviceFd)),
        )) {
            var lease = startLease
            repeat(25) {
                val (inv, next) = Fx.issue(Fx.validDraft(driver.userId), Fx.context(driver, actor), lease)
                assertTrue(numbers.add(inv.number.text), "duplicate ${inv.number}")
                lease = next
            }
        }
        assertEquals(50, numbers.size)
    }

    @Test
    fun `invalid drafts and bad leases issue nothing and do not advance the lease`() {
        val lease = Fx.lease(Fx.ab, Fx.deviceAb)
        val bad = Fx.validDraft().let { it.updateQuantity(it.lines[0].id, null, Fx.T0).getOrThrow() }
        val r = InvoiceIssuer.issue(bad, Fx.context(), lease)
        assertTrue(r is Outcome.Err && r.error is IssueError.Invalid)
        assertEquals(DriverSequence(100), lease.nextSequence)

        val revoked = InvoiceIssuer.issue(Fx.validDraft(), Fx.context(), lease.copy(revoked = true))
        assertEquals(Outcome.Err(IssueError.Lease(LeaseError.REVOKED)), revoked)
    }

    @Test
    fun `only the leased driver on their own device can issue`() {
        // FD's session trying to issue on AB's draft / lease.
        assertEquals(
            Outcome.Err(IssueError.ActorNotDriver),
            InvoiceIssuer.issue(Fx.validDraft(), Fx.context(actor = Fx.actorFd), Fx.lease(Fx.ab, Fx.deviceAb)),
        )
        // AB's identity but on FD's device: lease fencing rejects it.
        val wrongDevice = Fx.actorAb.copy(deviceId = Fx.deviceFd)
        assertEquals(
            Outcome.Err(IssueError.Lease(LeaseError.WRONG_DEVICE)),
            InvoiceIssuer.issue(Fx.validDraft(), Fx.context(actor = wrongDevice), Fx.lease(Fx.ab, Fx.deviceAb)),
        )
        assertEquals(
            Outcome.Err(IssueError.CustomerMismatch),
            InvoiceIssuer.issue(Fx.validDraft(), Fx.context(customerSnap = Fx.otherCustomer), Fx.lease(Fx.ab, Fx.deviceAb)),
        )
    }

    // ---------------- registration, void, timestamps ----------------

    private fun registration(inv: IssuedInvoice, n: Long, offsetMin: Long = 60) = ServerRegistration(
        inv.id, inv.number, RegisterReference(BusinessCode.of("BGI"), RegisterSequence(n)),
        Fx.T0.plus(Duration.ofMinutes(offsetMin)), Fx.T0.plus(Duration.ofMinutes(offsetMin)).plusMillis(5),
    )

    @Test
    fun `registration attaches PRE-N once, replays are idempotent, the customer number never changes`() {
        val inv = Fx.issue().invoice
        val reg = registration(inv, 101)
        val registered = inv.withRegistration(reg).getOrThrow()
        assertEquals("BGI-101", registered.registerReference?.text)
        assertEquals("BGI-100-AB", registered.number.text)
        assertEquals(inv.id, registered.id)
        assertEquals(inv.total, registered.total)
        assertSame(registered, registered.withRegistration(reg).getOrThrow())
        assertEquals(Outcome.Err(RegistrationError.CONFLICTING_REGISTER_REFERENCE), registered.withRegistration(registration(inv, 102)))
        // The original value is untouched.
        assertNull(inv.registerReference)
    }

    @Test
    fun `registration for another invoice or number is rejected`() {
        val inv = Fx.issue().invoice
        val other = registration(inv, 5).copy(invoiceId = InvoiceId.random())
        assertEquals(Outcome.Err(RegistrationError.WRONG_INVOICE), inv.withRegistration(other))
        val wrongNumber = registration(inv, 5).copy(
            customerInvoiceNumber = CustomerInvoiceNumber(BusinessCode.of("BGI"), DriverSequence(100), Fx.fd),
        )
        assertEquals(Outcome.Err(RegistrationError.NUMBER_MISMATCH), inv.withRegistration(wrongNumber))
        assertThrows<IllegalArgumentException> {
            ServerRegistration(inv.id, inv.number, RegisterReference(BusinessCode.of("BGI"), RegisterSequence(1)), Fx.T0, Fx.T0.minusSeconds(1))
        }
    }

    @Test
    fun `three timestamps are kept and device clock ahead of server is flagged (N11)`() {
        // Device clock set 2 days ahead; the server receives it 1 hour after real issue time.
        val inv =Fx.issue(ctx = Fx.context(at = Fx.T0.plus(Duration.ofDays(2)))).invoice
        val reg = registration(inv, 7)
        val registered = inv.withRegistration(reg).getOrThrow()
        assertEquals(Fx.T0.plus(Duration.ofDays(2)), registered.issuedAtDevice)
        assertEquals(reg.firstReceivedAtServer, registered.firstReceivedAtServer)
        assertEquals(reg.registeredAtServer, registered.registeredAtServer)
        assertTrue(registered.deviceClockAheadOfServer())

        val normal = Fx.issue().invoice.let { it.withRegistration(registration(it, 8)).getOrThrow() }
        assertFalse(normal.deviceClockAheadOfServer())
    }

    @Test
    fun `void is owner-only, needs a reason, keeps the number and values, and happens once (N13)`() {
        val inv = Fx.issue().invoice
        assertEquals(Outcome.Err(VoidError.NOT_AUTHORIZED), inv.void(Fx.actorAb, "wrong customer", Fx.T0))
        assertEquals(Outcome.Err(VoidError.REASON_REQUIRED), inv.void(Fx.owner, "  ", Fx.T0))
        val voided = inv.void(Fx.owner, "Wrong customer selected", Fx.T0).getOrThrow()
        assertEquals(InvoiceStatus.VOIDED, voided.status)
        assertEquals(inv.number, voided.number)
        assertEquals(inv.total, voided.total)
        assertEquals(inv.lines, voided.lines)
        assertEquals("Wrong customer selected", voided.voidRecord?.reason)
        assertEquals(Outcome.Err(VoidError.ALREADY_VOIDED), voided.void(Fx.owner, "again", Fx.T0))
        // N13: the next issue continues the sequence; the voided number is never recycled.
        val next = Fx.issue(lease = Fx.issue().updatedLease).invoice
        assertEquals("BGI-101-AB", next.number.text)
    }

    @Test
    fun `invoice lifecycle only allows draft to issued to voided`() {
        assertTrue(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.ISSUED))
        assertTrue(InvoiceStatus.ISSUED.canTransitionTo(InvoiceStatus.VOIDED))
        assertFalse(InvoiceStatus.ISSUED.canTransitionTo(InvoiceStatus.DRAFT))
        assertFalse(InvoiceStatus.VOIDED.canTransitionTo(InvoiceStatus.ISSUED))
        assertFalse(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.VOIDED))
    }

    @Test
    fun `restore round-trips and rejects stored totals that do not match the lines`() {
        val inv = Fx.issue().invoice.let { it.withRegistration(registration(it, 3)).getOrThrow() }
        fun restore(total: Money) = IssuedInvoice.restore(
            inv.id, inv.sourceDraftId, inv.number, inv.business, inv.customer, inv.driver, inv.issuedBy, inv.deviceId,
            inv.leaseEpoch, inv.catalogueVersion, inv.roundingPolicyId, inv.tax, inv.lines, inv.issuedAtDevice,
            inv.subtotal, inv.taxTotal, total, inv.registration, inv.voidRecord,
        )
        assertEquals(inv, restore(inv.total))
        assertThrows<IllegalArgumentException> { restore(inv.total + Money.ofMinor(1)) }
    }

    @Test
    fun `products with duplicate units cannot be defined`() {
        assertThrows<IllegalArgumentException> {
            Product(Fx.tomatoes.id, Fx.businessId, "X", "X", listOf(ProductUnitPrice(Fx.lb, null), ProductUnitPrice(Fx.lb, null)))
        }
    }
}
