package bb.produce.invoicing.domain

import bb.produce.invoicing.domain.catalogue.Business
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.catalogue.ProductUnitPrice
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.identity.DriverCode
import bb.produce.invoicing.domain.identity.DriverSequence
import bb.produce.invoicing.domain.identity.DriverSequenceLease
import bb.produce.invoicing.domain.identity.DriverSnapshot
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.invoice.InvoiceIssuer
import bb.produce.invoicing.domain.invoice.IssueContext
import bb.produce.invoicing.domain.invoice.Issuance
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.TaxSnapshot
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import java.time.Instant
import java.util.UUID

/** SYNTHETIC test data only — no real customers, drivers or businesses (invariant 9). */
object Fx {
    val T0: Instant = Instant.parse("2026-11-13T13:00:00Z") // 09:00 in Barbados

    fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    val businessId = BusinessId(uuid(1))
    val business = Business(businessId, BusinessCode.of("BGI"), "Synthetic Produce Co", "1 Test Road", "+1 246 000 0000")

    val customer = Customer(CustomerId(uuid(10)), businessId, "Test Grocer One", phone = "+1 246 000 0001")
    val otherCustomer = Customer(CustomerId(uuid(11)), businessId, "Test Grocer Two")

    val lb = UnitCode.of("lb")
    val kg = UnitCode.of("kg")
    val each = UnitCode.of("each")
    val case = UnitCode.of("case")

    val tomatoes = Product(ProductId(uuid(20)), businessId, "TOM-01", "Tomatoes",
        listOf(ProductUnitPrice(lb, UnitPrice.ofMinor(450)), ProductUnitPrice(kg, UnitPrice.ofMinor(990))))
    val limes = Product(ProductId(uuid(21)), businessId, "LIM-01", "Limes",
        listOf(ProductUnitPrice(each, UnitPrice.ofMinor(25)), ProductUnitPrice(case, UnitPrice.ofMinor(2_400))))
    val pumpkin = Product(ProductId(uuid(22)), businessId, "PUM-01", "Pumpkin (price on invoice)",
        listOf(ProductUnitPrice(lb, null)))
    val products = listOf(tomatoes, limes, pumpkin).associateBy { it.id }

    val catalogueV1 = CatalogueVersion(1)

    val driverAbUser = UserId(uuid(30))
    val driverFdUser = UserId(uuid(31))
    val ownerUser = UserId(uuid(32))
    val deviceAb = DeviceId(uuid(40))
    val deviceFd = DeviceId(uuid(41))
    val ownerTablet = DeviceId(uuid(42))

    val ab = DriverCode.of("AB")
    val fd = DriverCode.of("FD")
    val driverAb = DriverSnapshot(driverAbUser, ab, "Driver Alpha")
    val driverFd = DriverSnapshot(driverFdUser, fd, "Driver Foxtrot")
    val actorAb = Actor(driverAbUser, Role.DRIVER, deviceAb)
    val actorFd = Actor(driverFdUser, Role.DRIVER, deviceFd)
    val owner = Actor(ownerUser, Role.OWNER, ownerTablet)

    fun lease(code: DriverCode, device: DeviceId, next: Long = 100, epoch: Long = 1) =
        DriverSequenceLease(code, device, epoch, DriverSequence(next))

    /** A valid draft: 12.5 lb tomatoes @ 4.50 + 3 each limes @ 0.25. */
    fun validDraft(driver: UserId = driverAbUser, at: Instant = T0): InvoiceDraft {
        var d = InvoiceDraft.start(businessId, driver, catalogueV1, at).withCustomer(customer.id, at)
        d = d.addProduct(tomatoes, lb, at).getOrThrow()
        d = d.updateQuantity(d.lines[0].id, Quantity.of("12.5"), at).getOrThrow()
        d = d.addProduct(limes, each, at).getOrThrow()
        d = d.updateQuantity(d.lines[1].id, Quantity.of("3"), at).getOrThrow()
        return d
    }

    fun context(
        driver: DriverSnapshot = driverAb,
        actor: Actor = actorAb,
        at: Instant = T0,
        invoiceId: InvoiceId = InvoiceId.random(),
        customerSnap: Customer = customer,
    ) = IssueContext(
        invoiceId = invoiceId,
        business = business.snapshot(),
        customer = customerSnap.snapshot(),
        driver = driver,
        actor = actor,
        issuedAtDevice = at,
        catalogueVersion = catalogueV1,
        products = products,
        tax = TaxSnapshot.NotApplied(business.taxConfigVersion),
    )

    fun issue(draft: InvoiceDraft = validDraft(), ctx: IssueContext = context(), lease: DriverSequenceLease = lease(ab, deviceAb)): Issuance =
        when (val r = InvoiceIssuer.issue(draft, ctx, lease)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> error("issue failed: ${r.error}")
        }
}
