package bb.produce.invoicing.domain.catalogue

import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.money.Currency
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import java.math.BigDecimal

/** Business (tenant). `code` is the `PRE` part of invoice numbers and is frozen into issued invoices. */
data class Business(
    val id: BusinessId,
    val code: BusinessCode,
    val name: String,
    val address: String,
    val phone: String,
    val email: String? = null,
    val invoiceFooter: String? = null,
    val currency: Currency = Currency.BBD,
    val taxConfigVersion: Int = 1,
) {
    fun snapshot() = BusinessSnapshot(id, code, name, address, phone, email, invoiceFooter, currency)
}

data class BusinessSnapshot(
    val businessId: BusinessId,
    val code: BusinessCode,
    val name: String,
    val address: String,
    val phone: String,
    val email: String?,
    val invoiceFooter: String?,
    val currency: Currency,
)

data class Customer(
    val id: CustomerId,
    val businessId: BusinessId,
    val name: String,
    val contact: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val active: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "Customer name required" }
    }

    fun snapshot() = CustomerSnapshot(id, name.trim(), contact, phone, address)
}

data class CustomerSnapshot(
    val customerId: CustomerId,
    val name: String,
    val contact: String?,
    val phone: String?,
    val address: String?,
)

@JvmInline
value class CatalogueVersion(val value: Int) {
    init { require(value >= 1) { "Catalogue version must be >= 1" } }
}

/** A permitted selling unit for a product. `price == null` means "price entered on invoice" (v4 behaviour). */
data class ProductUnitPrice(val unit: UnitCode, val price: UnitPrice?)

data class Product(
    val id: ProductId,
    val businessId: BusinessId,
    val sku: String,
    val name: String,
    val units: List<ProductUnitPrice>,
    val active: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "Product name required" }
        require(units.isNotEmpty()) { "Product needs at least one unit" }
        require(units.map { it.unit }.toSet().size == units.size) { "Duplicate unit on product" }
    }

    fun allows(unit: UnitCode): Boolean = units.any { it.unit == unit }
    fun catalogueUnitPrice(unit: UnitCode): UnitPrice? = units.firstOrNull { it.unit == unit }?.price
}

/**
 * Explicit, versioned conversion from a selling unit to the product's stock base unit, e.g.
 * 1 `case` = 12 `each`. Never inferred from names (docs/09). Exact decimal arithmetic only.
 */
data class StockConversion(
    val productId: ProductId,
    val sellUnit: UnitCode,
    val baseUnit: UnitCode,
    val factor: BigDecimal,
    val version: Int,
) {
    init {
        require(factor.signum() > 0) { "Conversion factor must be positive" }
        require(factor.stripTrailingZeros().scale() <= 6) { "Conversion factor limited to 6 decimals" }
        require(version >= 1) { "Conversion version must be >= 1" }
    }

    fun toBase(quantity: Quantity): BaseQuantity =
        BaseQuantity(quantity.value.multiply(factor).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }, baseUnit)
}

/** Quantity in a product's stock base unit (e.g. `each`, `lb`). Exact decimal, may exceed 3 decimals after conversion. */
data class BaseQuantity(val value: BigDecimal, val unit: UnitCode) {
    override fun equals(other: Any?) = other is BaseQuantity && other.unit == unit && other.value.compareTo(value) == 0
    override fun hashCode() = 31 * unit.hashCode() + value.stripTrailingZeros().hashCode()
    override fun toString() = "${value.stripTrailingZeros().toPlainString()} $unit"
}

/** Lookup of the conversions in force; missing mapping is an error, never a 1:1 guess. */
class ConversionTable(conversions: List<StockConversion>) {
    private val byKey: Map<Pair<ProductId, UnitCode>, StockConversion>

    init {
        val map = LinkedHashMap<Pair<ProductId, UnitCode>, StockConversion>()
        conversions.forEach {
            require(map.put(it.productId to it.sellUnit, it) == null) { "Duplicate conversion for ${it.productId}/${it.sellUnit}" }
        }
        byKey = map
    }

    fun find(productId: ProductId, sellUnit: UnitCode): StockConversion? = byKey[productId to sellUnit]
}
