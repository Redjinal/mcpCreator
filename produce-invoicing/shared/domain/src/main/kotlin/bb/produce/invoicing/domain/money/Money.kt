package bb.produce.invoicing.domain.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Only Barbados dollars are supported in the MVP (multi-currency is out of scope). */
enum class Currency(val code: String, val minorDigits: Int) { BBD("BBD", 2) }

/**
 * Fixed-precision money in integer minor units (cents). Never binary floating point.
 * Arithmetic is overflow-checked; an overflow is a programming/limit error, not silently wrapped.
 */
@ConsistentCopyVisibility
data class Money private constructor(val minor: Long, val currency: Currency) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(minor, sameCurrency(other).minor), currency)
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, sameCurrency(other).minor), currency)
    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)
    override fun compareTo(other: Money): Int = minor.compareTo(sameCurrency(other).minor)

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L

    /** Exact decimal value, e.g. 1234 cents -> 12.34. */
    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, currency.minorDigits)

    /** `12.34` with thousands grouping, no currency code. */
    fun formatAmount(): String = amountFormat().format(toDecimal())

    /** `BBD 1,234.56` / `BBD -5.00`. Every monetary output shows the currency (docs/06). */
    fun format(): String = "${currency.code} ${formatAmount()}"

    override fun toString(): String = format()

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other
    }

    companion object {
        fun ofMinor(minor: Long, currency: Currency = Currency.BBD) = Money(minor, currency)
        fun zero(currency: Currency = Currency.BBD) = Money(0, currency)

        /**
         * Parses a plain decimal amount such as `4.5` or `4.50`. Rejects more than 2 decimals,
         * exponents, grouping separators and anything that is not a plain number.
         */
        fun parse(text: String, currency: Currency = Currency.BBD): Money? {
            val t = text.trim()
            if (!PLAIN_DECIMAL.matches(t)) return null
            val d = BigDecimal(t)
            if (d.scale() > currency.minorDigits) return null
            return try {
                Money(d.movePointRight(currency.minorDigits).setScale(0, RoundingMode.UNNECESSARY).longValueExact(), currency)
            } catch (e: ArithmeticException) {
                null
            }
        }

        fun sum(items: Iterable<Money>, currency: Currency = Currency.BBD): Money =
            items.fold(zero(currency)) { acc, m -> acc + m }

        private val PLAIN_DECIMAL = Regex("""-?\d{1,15}(\.\d+)?""")

        private fun amountFormat() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).apply {
            roundingMode = RoundingMode.UNNECESSARY
        }
    }
}
