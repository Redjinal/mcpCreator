package bb.produce.invoicing.core.data.db

import bb.produce.invoicing.domain.catalogue.BaseQuantity
import bb.produce.invoicing.domain.catalogue.Business
import bb.produce.invoicing.domain.catalogue.BusinessSnapshot
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.CustomerSnapshot
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.catalogue.ProductUnitPrice
import bb.produce.invoicing.domain.catalogue.StockConversion
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.identity.CustomerInvoiceNumber
import bb.produce.invoicing.domain.identity.DriverCode
import bb.produce.invoicing.domain.identity.DriverSequence
import bb.produce.invoicing.domain.identity.DriverSequenceLease
import bb.produce.invoicing.domain.identity.DriverSnapshot
import bb.produce.invoicing.domain.identity.RegisterReference
import bb.produce.invoicing.domain.identity.RegisterSequence
import bb.produce.invoicing.domain.invoice.DraftId
import bb.produce.invoicing.domain.invoice.DraftLine
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.invoice.IssuedLine
import bb.produce.invoicing.domain.invoice.ServerRegistration
import bb.produce.invoicing.domain.invoice.SyncState
import bb.produce.invoicing.domain.invoice.VoidRecord
import bb.produce.invoicing.domain.money.Currency
import bb.produce.invoicing.domain.money.Money
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.TaxSnapshot
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import bb.produce.invoicing.domain.payment.PaymentEvent
import bb.produce.invoicing.domain.payment.PaymentEventKind
import bb.produce.invoicing.domain.payment.PaymentMethod
import bb.produce.invoicing.domain.stock.SaleReference
import bb.produce.invoicing.domain.stock.StockBucket
import bb.produce.invoicing.domain.stock.StockMovement
import bb.produce.invoicing.domain.stock.StockMovementKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/*
 * Row <-> domain mapping. Reading never trusts a row blindly: issued invoices go through
 * IssuedInvoice.restore, which recomputes totals; codes and numbers are re-parsed and cross-checked.
 */

internal fun String.uuid(): UUID = UUID.fromString(this)
internal fun Long.instant(): Instant = Instant.ofEpochMilli(this)
internal fun Instant.millis(): Long = toEpochMilli()

// ---- business / catalogue ----

internal fun BusinessEntity.toDomain() = Business(
    BusinessId(id.uuid()), BusinessCode.of(code), name, address, phone, email, invoiceFooter,
    Currency.valueOf(currency), taxConfigVersion,
)

internal fun Business.toEntity(catalogueVersion: Int, negativeStockPolicy: String) = BusinessEntity(
    id.toString(), code.value, name, address, phone, email, invoiceFooter, currency.name, taxConfigVersion,
    catalogueVersion, negativeStockPolicy,
)

internal fun CustomerEntity.toDomain() = Customer(
    CustomerId(id.uuid()), BusinessId(businessId.uuid()), name, contact, phone, address, active,
)

internal fun Customer.toEntity(originDevice: DeviceId?) = CustomerEntity(
    id.toString(), businessId.toString(), name, contact, phone, address, active, originDevice?.toString(), null,
)

internal fun toProduct(p: ProductEntity, prices: List<ProductUnitPriceEntity>) = Product(
    ProductId(p.id.uuid()), BusinessId(p.businessId.uuid()), p.sku, p.name,
    prices.map { ProductUnitPrice(UnitCode.of(it.unit), it.priceMinor?.let(UnitPrice::ofMinor)) },
    p.active,
)

internal fun Product.toEntities(): Pair<ProductEntity, List<ProductUnitPriceEntity>> =
    ProductEntity(id.toString(), businessId.toString(), sku, name, active) to
        units.map { ProductUnitPriceEntity(id.toString(), it.unit.code, it.price?.minor) }

internal fun StockConversionEntity.toDomain() =
    StockConversion(ProductId(productId.uuid()), UnitCode.of(sellUnit), UnitCode.of(baseUnit), BigDecimal(factor), version)

internal fun StockConversion.toEntity() =
    StockConversionEntity(productId.toString(), sellUnit.code, baseUnit.code, factor.toPlainString(), version)

// ---- lease ----

internal fun DriverLeaseEntity.toDomain() = DriverSequenceLease(
    DriverCode.of(driverCode), DeviceId(deviceId.uuid()), epoch, nextSequence?.let(::DriverSequence), revoked, validUntil?.instant(),
)

internal fun DriverLeaseEntity.driverSnapshot() = DriverSnapshot(UserId(driverUserId.uuid()), DriverCode.of(driverCode), driverDisplayName)

// ---- drafts ----

internal fun toDraft(d: DraftEntity, lines: List<DraftLineEntity>) = InvoiceDraft(
    id = DraftId(d.id.uuid()),
    businessId = BusinessId(d.businessId.uuid()),
    driverUserId = UserId(d.driverUserId.uuid()),
    customerId = d.customerId?.let { CustomerId(it.uuid()) },
    lines = lines.sortedBy { it.position }.map {
        DraftLine(
            id = LineId(it.id.uuid()),
            productId = it.productId?.let { p -> ProductId(p.uuid()) },
            description = it.description,
            unit = UnitCode.of(it.unit),
            quantity = it.quantity?.let(Quantity::of),
            unitPrice = it.unitPriceMinor?.let(UnitPrice::ofMinor),
            catalogueUnitPrice = it.catalogueUnitPriceMinor?.let(UnitPrice::ofMinor),
            priceEdited = it.priceEdited,
            zeroPriceConfirmed = it.zeroPriceConfirmed,
        )
    },
    catalogueVersion = CatalogueVersion(d.catalogueVersion),
    updatedAt = d.updatedAt.instant(),
)

internal fun InvoiceDraft.toEntities(): Pair<DraftEntity, List<DraftLineEntity>> =
    DraftEntity(id.toString(), businessId.toString(), driverUserId.toString(), customerId?.toString(), catalogueVersion.value, updatedAt.millis()) to
        lines.mapIndexed { i, l ->
            DraftLineEntity(
                l.id.toString(), id.toString(), i + 1, l.productId?.toString(), l.description, l.unit.code,
                l.quantity?.value?.toPlainString(), l.unitPrice?.minor, l.catalogueUnitPrice?.minor, l.priceEdited, l.zeroPriceConfirmed,
            )
        }

// ---- issued invoices ----

private const val TAX_NOT_APPLIED = "NOT_APPLIED"

internal fun IssuedInvoice.toEntities(syncState: SyncState): Pair<InvoiceEntity, List<InvoiceLineEntity>> {
    val taxKind = when (tax) { is TaxSnapshot.NotApplied -> TAX_NOT_APPLIED }
    val e = InvoiceEntity(
        id = id.toString(), sourceDraftId = sourceDraftId.toString(), businessId = business.businessId.toString(),
        customerInvoiceNumber = number.text, businessCode = number.businessCode.value, driverCode = number.driverCode.value,
        driverSequence = number.sequence.value,
        bizName = business.name, bizAddress = business.address, bizPhone = business.phone, bizEmail = business.email,
        bizFooter = business.invoiceFooter, currency = business.currency.name,
        customerId = customer.customerId.toString(), custName = customer.name, custContact = customer.contact,
        custPhone = customer.phone, custAddress = customer.address,
        driverUserId = driver.userId.toString(), driverDisplayName = driver.displayName,
        issuedBy = issuedBy.toString(), deviceId = deviceId.toString(), leaseEpoch = leaseEpoch,
        catalogueVersion = catalogueVersion.value, roundingPolicyId = roundingPolicyId,
        taxKind = taxKind, taxConfigVersion = tax.configVersion,
        subtotalMinor = subtotal.minor, taxMinor = taxTotal.minor, totalMinor = total.minor,
        issuedAtDevice = issuedAtDevice.millis(),
        registerBusinessCode = registration?.registerReference?.businessCode?.value,
        registerSequence = registration?.registerReference?.sequence?.value,
        firstReceivedAtServer = registration?.firstReceivedAtServer?.millis(),
        registeredAtServer = registration?.registeredAtServer?.millis(),
        voidReason = voidRecord?.reason, voidedBy = voidRecord?.by?.toString(),
        voidDeviceId = voidRecord?.deviceId?.toString(), voidedAt = voidRecord?.at?.millis(),
        syncState = syncState.name,
    )
    val lines = lines.map {
        InvoiceLineEntity(
            it.lineId.toString(), id.toString(), it.position, it.productId?.toString(), it.description, it.unit.code,
            it.quantity.value.toPlainString(), it.unitPrice.minor, it.lineTotal.minor, it.catalogueUnitPrice?.minor, it.zeroPriceConfirmed,
        )
    }
    return e to lines
}

internal fun toIssuedInvoice(e: InvoiceEntity, lines: List<InvoiceLineEntity>): IssuedInvoice {
    val number = CustomerInvoiceNumber(BusinessCode.of(e.businessCode), DriverSequence(e.driverSequence), DriverCode.of(e.driverCode))
    check(number.text == e.customerInvoiceNumber) { "Corrupt invoice row ${e.id}: number columns disagree" }
    val id = InvoiceId(e.id.uuid())
    val tax = when (e.taxKind) {
        TAX_NOT_APPLIED -> TaxSnapshot.NotApplied(e.taxConfigVersion)
        else -> error("Unknown tax kind ${e.taxKind}")
    }
    val registration = e.registerSequence?.let {
        ServerRegistration(
            id, number,
            RegisterReference(BusinessCode.of(requireNotNull(e.registerBusinessCode)), RegisterSequence(it)),
            requireNotNull(e.firstReceivedAtServer).instant(), requireNotNull(e.registeredAtServer).instant(),
        )
    }
    val void = e.voidedAt?.let {
        VoidRecord(requireNotNull(e.voidReason), UserId(requireNotNull(e.voidedBy).uuid()), DeviceId(requireNotNull(e.voidDeviceId).uuid()), it.instant())
    }
    return IssuedInvoice.restore(
        id = id,
        sourceDraftId = DraftId(e.sourceDraftId.uuid()),
        number = number,
        business = BusinessSnapshot(BusinessId(e.businessId.uuid()), number.businessCode, e.bizName, e.bizAddress, e.bizPhone, e.bizEmail, e.bizFooter, Currency.valueOf(e.currency)),
        customer = CustomerSnapshot(CustomerId(e.customerId.uuid()), e.custName, e.custContact, e.custPhone, e.custAddress),
        driver = DriverSnapshot(UserId(e.driverUserId.uuid()), number.driverCode, e.driverDisplayName),
        issuedBy = UserId(e.issuedBy.uuid()),
        deviceId = DeviceId(e.deviceId.uuid()),
        leaseEpoch = e.leaseEpoch,
        catalogueVersion = CatalogueVersion(e.catalogueVersion),
        roundingPolicyId = e.roundingPolicyId,
        tax = tax,
        lines = lines.sortedBy { it.position }.map {
            IssuedLine(
                LineId(it.id.uuid()), it.position, it.productId?.let { p -> ProductId(p.uuid()) }, it.description,
                UnitCode.of(it.unit), Quantity.of(it.quantity), UnitPrice.ofMinor(it.unitPriceMinor), Money.ofMinor(it.lineTotalMinor),
                it.catalogueUnitPriceMinor?.let(UnitPrice::ofMinor), it.zeroPriceConfirmed,
            )
        },
        issuedAtDevice = e.issuedAtDevice.instant(),
        storedSubtotal = Money.ofMinor(e.subtotalMinor),
        storedTaxTotal = Money.ofMinor(e.taxMinor),
        storedTotal = Money.ofMinor(e.totalMinor),
        registration = registration,
        voidRecord = void,
    )
}

// ---- stock ----

internal fun StockMovement.toEntity(businessId: BusinessId) = StockMovementEntity(
    id = id.toString(), businessId = businessId.toString(), shiftId = shiftId.toString(), locationId = locationId.toString(),
    productId = productId.toString(), kind = kind.name, bucket = bucket.name,
    quantity = quantity.value.toPlainString(), unit = quantity.unit.code,
    saleInvoiceId = saleReference?.invoiceId?.toString(), saleLineId = saleReference?.lineId?.toString(),
    conversionVersion = conversionVersion, reversesMovementId = reversesMovementId?.toString(), reason = reason,
    actorId = recordedBy.userId.toString(), actorRole = recordedBy.role.name, deviceId = recordedBy.deviceId.toString(),
    at = at.millis(),
)

internal fun StockMovementEntity.toDomain() = StockMovement(
    id = EventId(id.uuid()), shiftId = ShiftId(shiftId.uuid()), locationId = LocationId(locationId.uuid()),
    productId = ProductId(productId.uuid()), kind = StockMovementKind.valueOf(kind), bucket = StockBucket.valueOf(bucket),
    quantity = BaseQuantity(BigDecimal(quantity), UnitCode.of(unit)),
    recordedBy = Actor(UserId(actorId.uuid()), Role.valueOf(actorRole), DeviceId(deviceId.uuid())),
    at = at.instant(),
    saleReference = if (saleInvoiceId != null && saleLineId != null) SaleReference(InvoiceId(saleInvoiceId.uuid()), LineId(saleLineId.uuid())) else null,
    conversionVersion = conversionVersion,
    reversesMovementId = reversesMovementId?.let { EventId(it.uuid()) },
    reason = reason,
)

// ---- payments ----

internal fun PaymentEvent.toEntity() = PaymentEventEntity(
    id.toString(), invoiceId.toString(), kind.name, method.name, amount.minor, recordedBy.toString(), deviceId.toString(),
    at.millis(), externalReference, reversesEventId?.toString(), reason,
)

internal fun PaymentEventEntity.toDomain() = PaymentEvent(
    EventId(id.uuid()), InvoiceId(invoiceId.uuid()), PaymentEventKind.valueOf(kind), PaymentMethod.valueOf(method),
    Money.ofMinor(amountMinor), UserId(recordedBy.uuid()), DeviceId(deviceId.uuid()), at.instant(), externalReference,
    reversesEventId?.let { EventId(it.uuid()) }, reason,
)
