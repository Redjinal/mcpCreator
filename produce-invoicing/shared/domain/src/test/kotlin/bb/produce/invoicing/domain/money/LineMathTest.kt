package bb.produce.invoicing.domain.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.random.Random

class LineMathTest {

    private fun total(qty: String, price: String) =
        LineMath.lineTotal(Quantity.of(qty), UnitPrice.parse(price).getOrThrow()).minor

    @ParameterizedTest(name = "{0} x {1} = {2} cents")
    @CsvSource(
        "12.5, 4.50, 5625",     // decimal weight
        "3, 0.25, 75",
        "0.333, 1.00, 33",      // 33.3 -> 33
        "0.335, 1.00, 34",      // 33.5 -> 34 (HALF_UP)
        "1.005, 1.00, 101",     // 100.5 -> 101
        "2.125, 0.99, 210",     // 210.375 -> 210
        "0.001, 0.01, 0",       // 0.001 cent -> 0
        "0.05, 0.01, 0",        // 0.05 cent -> 0 (rounds down below half)
        "0.5, 0.01, 1",         // exactly half a cent -> 1
        "99999, 999999.00, 9999890000100",
        "1, 0.00, 0",
    )
    fun `line totals use exact decimals and HALF_UP to the cent`(qty: String, price: String, cents: Long) {
        assertEquals(cents, total(qty, price))
    }

    @Test
    fun `matches the v4 prototype cents() formula on 20k random lines`() {
        // v4: Math.round(Math.round(qty*1000) * Math.round(price*100) / 1000), positive values only.
        val rnd = Random(20261113)
        repeat(20_000) {
            val qtyMilli = rnd.nextLong(1, 99_999_000)
            val priceCents = rnd.nextLong(0, 99_999_900)
            val expected = (qtyMilli * priceCents + 500) / 1000 // integer HALF_UP for non-negatives
            val actual = LineMath.lineTotal(
                Quantity.of(BigDecimal.valueOf(qtyMilli, 3)),
                UnitPrice.ofMinor(priceCents),
            ).minor
            assertEquals(expected, actual, "qty=${BigDecimal.valueOf(qtyMilli, 3)} price=$priceCents")
        }
    }

    @Test
    fun `rounding policy is recorded and still provisional`() {
        assertEquals("LINE_HALF_UP_CENTS_V1", RoundingPolicy.id)
        assertFalse(RoundingPolicy.APPROVED_BY_ACCOUNTANT)
    }

    @Test
    fun `no tax is applied until a treatment is approved`() {
        assertEquals(Money.zero(), TaxSnapshot.NotApplied(1).taxOn(Money.ofMinor(12_345)))
    }
}
