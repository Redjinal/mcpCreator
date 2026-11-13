package bb.produce.invoicing.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Invoice arithmetic.
 *
 * PROVISIONAL — subject to accountant approval (docs/03, docs/07 D04, B00-5):
 *  - each line is rounded to the cent with HALF_UP, then lines are summed (same as the v4 `cents()`);
 *  - tax is not applied (no VAT rate has been approved).
 * The policy is recorded on every issued invoice ([RoundingPolicy.id]) so a later change can never
 * silently alter how an existing invoice was computed.
 */
object LineMath {

    /** quantity × unit price, rounded to whole cents HALF_UP. Exact decimal arithmetic. */
    fun lineTotal(quantity: Quantity, unitPrice: UnitPrice): Money {
        val exactCents = quantity.value.multiply(BigDecimal.valueOf(unitPrice.minor))
        return Money.ofMinor(exactCents.setScale(0, RoundingPolicy.LINE_ROUNDING).longValueExact())
    }
}

object RoundingPolicy {
    /** Identifier frozen into issued invoices. Bump when the approved rule changes. */
    const val id: String = "LINE_HALF_UP_CENTS_V1"
    val LINE_ROUNDING: RoundingMode = RoundingMode.HALF_UP
    const val APPROVED_BY_ACCOUNTANT: Boolean = false
}

/**
 * Tax as frozen on an invoice. Only [NotApplied] exists until the client's accountant approves
 * a tax treatment (D04) — no VAT rate is invented here.
 */
sealed interface TaxSnapshot {
    val configVersion: Int
    fun taxOn(subtotal: Money): Money

    data class NotApplied(override val configVersion: Int) : TaxSnapshot {
        override fun taxOn(subtotal: Money): Money = Money.zero(subtotal.currency)
    }
}
