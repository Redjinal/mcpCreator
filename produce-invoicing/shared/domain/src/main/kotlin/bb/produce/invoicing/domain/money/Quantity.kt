package bb.produce.invoicing.domain.money

import bb.produce.invoicing.domain.common.Outcome
import java.math.BigDecimal

/**
 * A selling/stock unit code such as `lb`, `kg`, `each`, `case`. Normalised to lower case.
 * Units are never converted implicitly; conversions are explicit per product (see StockConversion).
 */
@JvmInline
value class UnitCode private constructor(val code: String) {
    override fun toString() = code

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9]{0,11}")

        fun of(raw: String): UnitCode {
            val n = raw.trim().lowercase()
            require(PATTERN.matches(n)) { "Invalid unit code '$raw'" }
            return UnitCode(n)
        }

        fun parse(raw: String): UnitCode? = runCatching { of(raw) }.getOrNull()

        /** Units shown in the v4 prototype; the business's real list is client-configured. */
        val PROTOTYPE_DEFAULTS: List<UnitCode> =
            listOf("lb", "kg", "each", "dozen", "bunch", "hand", "bag", "box", "case").map(::of)
    }
}

enum class QuantityError { EMPTY, NOT_A_NUMBER, NEGATIVE, ZERO, TOO_MANY_DECIMALS, TOO_LARGE }

/**
 * A selling quantity: exact decimal, strictly positive, at most [MAX_SCALE] decimals and at most [MAX].
 * Stored with a fixed scale of 3 so `12.5` and `12.500` are the same value.
 */
@ConsistentCopyVisibility
data class Quantity private constructor(val value: BigDecimal) : Comparable<Quantity> {

    override fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    /** Canonical text without trailing zeros: `12.5`, `3`, `0.125`. */
    fun toPlainString(): String = value.stripTrailingZeros().toPlainString()

    override fun toString(): String = toPlainString()

    operator fun plus(other: Quantity): Quantity = of(value + other.value)

    companion object {
        const val MAX_SCALE = 3
        val MAX: BigDecimal = BigDecimal("99999")

        fun of(value: BigDecimal): Quantity = when (val r = validate(value)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw IllegalArgumentException("Invalid quantity $value: ${r.error}")
        }

        fun of(text: String): Quantity = parse(text).getOrThrow()

        /** Parses user input. Accepts a plain decimal with `.` (the UI normalises `,` before calling). */
        fun parse(text: String?): Outcome<Quantity, QuantityError> {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return Outcome.Err(QuantityError.EMPTY)
            if (!PLAIN.matches(t)) return Outcome.Err(QuantityError.NOT_A_NUMBER)
            return validate(BigDecimal(t))
        }

        private fun validate(v: BigDecimal): Outcome<Quantity, QuantityError> {
            val stripped = v.stripTrailingZeros()
            return when {
                v.signum() < 0 -> Outcome.Err(QuantityError.NEGATIVE)
                v.signum() == 0 -> Outcome.Err(QuantityError.ZERO)
                stripped.scale() > MAX_SCALE -> Outcome.Err(QuantityError.TOO_MANY_DECIMALS)
                v > MAX -> Outcome.Err(QuantityError.TOO_LARGE)
                else -> Outcome.Ok(Quantity(v.setScale(MAX_SCALE)))
            }
        }

        private val PLAIN = Regex("""-?\d{1,12}(\.\d{1,12})?""")
    }
}

enum class UnitPriceError { EMPTY, NOT_A_NUMBER, NEGATIVE, TOO_MANY_DECIMALS, TOO_LARGE }

/** Price per selling unit in cents. Zero is allowed but must be explicitly confirmed on a line. */
@ConsistentCopyVisibility
data class UnitPrice private constructor(val money: Money) {
    val minor: Long get() = money.minor
    val isZero: Boolean get() = money.isZero
    override fun toString(): String = money.format()

    companion object {
        /** BBD 999,999.00 per unit (v4 limit); confirm with the client. */
        const val MAX_MINOR = 99_999_900L

        fun ofMinor(minor: Long): UnitPrice {
            require(minor in 0..MAX_MINOR) { "Unit price out of range: $minor" }
            return UnitPrice(Money.ofMinor(minor))
        }

        fun parse(text: String?): Outcome<UnitPrice, UnitPriceError> {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return Outcome.Err(UnitPriceError.EMPTY)
            if (!Regex("""-?\d{1,12}(\.\d{1,12})?""").matches(t)) return Outcome.Err(UnitPriceError.NOT_A_NUMBER)
            val d = BigDecimal(t)
            return when {
                d.signum() < 0 -> Outcome.Err(UnitPriceError.NEGATIVE)
                d.stripTrailingZeros().scale() > 2 -> Outcome.Err(UnitPriceError.TOO_MANY_DECIMALS)
                else -> {
                    val minor = d.movePointRight(2).longValueExact()
                    if (minor > MAX_MINOR) Outcome.Err(UnitPriceError.TOO_LARGE) else Outcome.Ok(ofMinor(minor))
                }
            }
        }
    }
}
