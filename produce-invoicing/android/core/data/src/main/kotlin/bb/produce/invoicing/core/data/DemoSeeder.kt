package bb.produce.invoicing.core.data

import androidx.room.withTransaction
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.core.data.db.DriverLeaseEntity
import bb.produce.invoicing.core.data.db.LocationEntity
import bb.produce.invoicing.core.data.db.ShiftEntity
import bb.produce.invoicing.core.data.db.millis
import bb.produce.invoicing.core.data.db.toEntities
import bb.produce.invoicing.core.data.db.toEntity
import bb.produce.invoicing.domain.catalogue.BaseQuantity
import bb.produce.invoicing.domain.catalogue.Business
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.catalogue.ProductUnitPrice
import bb.produce.invoicing.domain.catalogue.StockConversion
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BarbadosTime
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.LocationId
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.ShiftId
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import bb.produce.invoicing.domain.stock.StockBucket
import bb.produce.invoicing.domain.stock.StockMovement
import bb.produce.invoicing.domain.stock.StockMovementKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * SYNTHETIC demo data for developer builds only (invariant 9: no real customers/businesses).
 * Every name says it is synthetic. Pilot builds must never call this.
 */
object DemoData {
    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    val businessId = BusinessId(id(1))
    val ownerUser = UserId(id(2))
    val driverUser = UserId(id(3))
    val warehouse = LocationId(id(4))
    val vehicle = LocationId(id(5))
    val shift = ShiftId(id(6))

    val business = Business(businessId, BusinessCode.of("DEMO"), "Demo Produce Co (SYNTHETIC)", "1 Example Road (synthetic)", "+1 246 000 0000")

    val customers = listOf(
        Customer(CustomerId(id(10)), businessId, "Sample Grocer A (synthetic)", phone = "+1 246 000 0010"),
        Customer(CustomerId(id(11)), businessId, "Sample Restaurant B (synthetic)"),
        Customer(CustomerId(id(12)), businessId, "Sample Market Stall C (synthetic)"),
    )

    private val lb = UnitCode.of("lb")
    private val kg = UnitCode.of("kg")
    private val each = UnitCode.of("each")
    private val case = UnitCode.of("case")
    private val bunch = UnitCode.of("bunch")

    val products = listOf(
        Product(ProductId(id(20)), businessId, "SYN-TOM", "Tomatoes", listOf(ProductUnitPrice(lb, UnitPrice.ofMinor(450)), ProductUnitPrice(kg, UnitPrice.ofMinor(990)))),
        Product(ProductId(id(21)), businessId, "SYN-LIM", "Limes", listOf(ProductUnitPrice(each, UnitPrice.ofMinor(25)), ProductUnitPrice(case, UnitPrice.ofMinor(2_400)))),
        Product(ProductId(id(22)), businessId, "SYN-PUM", "Pumpkin (price on invoice)", listOf(ProductUnitPrice(lb, null))),
        Product(ProductId(id(23)), businessId, "SYN-THY", "Thyme", listOf(ProductUnitPrice(bunch, UnitPrice.ofMinor(300)))),
    )

    val conversions = listOf(
        StockConversion(products[0].id, lb, lb, BigDecimal.ONE, 1),
        StockConversion(products[0].id, kg, lb, BigDecimal("2.20462"), 1),
        StockConversion(products[1].id, each, each, BigDecimal.ONE, 1),
        StockConversion(products[1].id, case, each, BigDecimal("12"), 1),
        StockConversion(products[2].id, lb, lb, BigDecimal.ONE, 1),
        StockConversion(products[3].id, bunch, bunch, BigDecimal.ONE, 1),
    )

    /** Opening load per product, in base units. */
    val openingLoad = mapOf(products[0].id to "200", products[1].id to "600", products[2].id to "150", products[3].id to "40")
}

class DemoSeeder(private val db: AppDatabase) {

    /** Seeds once; returns false if a business already exists. Binds the demo driver's lease to [device]. */
    suspend fun seedIfEmpty(device: DeviceId, at: Instant, driverCode: String = "AB", startSequence: Long = 100): Boolean = db.withTransaction {
        if (db.businessDao().first() != null) return@withTransaction false
        val d = DemoData
        db.businessDao().insert(d.business.toEntity(catalogueVersion = 1, negativeStockPolicy = "BLOCK"))
        d.customers.forEach { db.catalogueDao().insertCustomer(it.toEntity(null)) }
        d.products.forEach { p ->
            val (row, prices) = p.toEntities()
            db.catalogueDao().insertProduct(row)
            db.catalogueDao().insertPrices(prices)
        }
        db.stockDao().insertConversions(d.conversions.map { it.toEntity() })
        db.leaseDao().insert(
            DriverLeaseEntity(d.businessId.toString(), driverCode, d.driverUser.toString(), "Demo Driver (synthetic)",
                device.toString(), epoch = 1, nextSequence = startSequence, revoked = false, validUntil = null, updatedAt = at.millis()),
        )
        db.stockDao().insertLocation(LocationEntity(d.warehouse.toString(), d.businessId.toString(), "WAREHOUSE", "Demo warehouse"))
        db.stockDao().insertLocation(LocationEntity(d.vehicle.toString(), d.businessId.toString(), "VEHICLE", "Demo van 1"))
        db.stockDao().insertShift(
            ShiftEntity(d.shift.toString(), d.businessId.toString(), d.driverUser.toString(), device.toString(), d.vehicle.toString(),
                BarbadosTime.localDate(at).toString(), "OPEN", null, at.millis()),
        )
        val owner = Actor(d.ownerUser, Role.OWNER, device)
        db.stockDao().insertMovements(
            d.openingLoad.map { (productId, qty) ->
                val baseUnit = d.conversions.first { it.productId == productId }.baseUnit
                StockMovement(EventId.random(), d.shift, d.vehicle, productId, StockMovementKind.OPENING_LOAD, StockBucket.SALEABLE,
                    BaseQuantity(BigDecimal(qty), baseUnit), owner, at).toEntity(d.businessId)
            },
        )
        true
    }
}
