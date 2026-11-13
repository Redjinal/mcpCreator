package bb.produce.invoicing.domain.money

import bb.produce.invoicing.domain.common.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class MoneyQuantityTest {

    @Test
    fun `0_1 plus 0_2 is exactly 0_3 in money and quantity`() {
        assertEquals(Money.parse("0.3"), Money.parse("0.1")!! + Money.parse("0.2")!!)
        assertEquals(Quantity.of("0.3"), Quantity.of("0.1") + Quantity.of("0.2"))
        // The binary floating point result is NOT 0.3 — which is why it is never used for money.
        assertTrue(0.1 + 0.2 != 0.3)
    }

    @Test
    fun `money formats with currency code and grouping`() {
        assertEquals("BBD 1,234.56", Money.ofMinor(123_456).format())
        assertEquals("BBD 0.05", Money.ofMinor(5).format())
        assertEquals("BBD -5.00", Money.ofMinor(-500).format())
    }

    @Test
    fun `money arithmetic overflow is an error, not a wrap-around`() {
        assertThrows<ArithmeticException> { Money.ofMinor(Long.MAX_VALUE) + Money.ofMinor(1) }
    }

    @ParameterizedTest
    @CsvSource("4.5,450", "4.50,450", "0,0", "999999.99,99999999", "-1.25,-125")
    fun `money parses plain decimals exactly`(text: String, minor: Long) {
        assertEquals(Money.ofMinor(minor), Money.parse(text))
    }

    @ParameterizedTest
    @CsvSource("4.505", "1e3", "'1,000.00'", "abc", "''", "4.")
    fun `money rejects ambiguous input`(text: String) {
        assertNull(Money.parse(text))
    }

    @Test
    fun `quantity is normalised to scale 3 and compares by value`() {
        assertEquals(Quantity.of("12.5"), Quantity.of("12.500"))
        assertEquals(BigDecimal("12.500"), Quantity.of("12.5").value)
        assertEquals("12.5", Quantity.of("12.500").toPlainString())
        assertEquals("3", Quantity.of("3").toPlainString())
    }

    @ParameterizedTest
    @CsvSource(
        "'',EMPTY", "'  ',EMPTY", "abc,NOT_A_NUMBER", "1e3,NOT_A_NUMBER", "'1,5',NOT_A_NUMBER",
        "-1,NEGATIVE", "0,ZERO", "0.000,ZERO", "1.2345,TOO_MANY_DECIMALS", "100000,TOO_LARGE", "99999.001,TOO_LARGE",
    )
    fun `invalid quantities are rejected with a specific reason`(text: String, expected: QuantityError) {
        assertEquals(Outcome.Err(expected), Quantity.parse(text))
    }

    @ParameterizedTest
    @CsvSource("0.001", "1.25", "99999", "' 2 '", "1.2340")
    fun `valid quantities are accepted`(text: String) {
        assertTrue(Quantity.parse(text).isOk)
    }

    @ParameterizedTest
    @CsvSource("'',EMPTY", "-0.01,NEGATIVE", "1.234,TOO_MANY_DECIMALS", "1000000,TOO_LARGE", "x,NOT_A_NUMBER")
    fun `invalid unit prices are rejected`(text: String, expected: UnitPriceError) {
        assertEquals(Outcome.Err(expected), UnitPrice.parse(text))
    }

    @Test
    fun `zero unit price parses but is flagged as zero`() {
        assertTrue(UnitPrice.parse("0.00").getOrThrow().isZero)
        assertEquals(99_999_900L, UnitPrice.parse("999999").getOrThrow().minor)
    }

    @ParameterizedTest
    @CsvSource("LB,lb", "' kg ',kg", "each,each")
    fun `unit codes normalise to lower case`(raw: String, code: String) {
        assertEquals(code, UnitCode.of(raw).code)
    }

    @ParameterizedTest
    @CsvSource("''", "1lb", "l b", "a-b", "verylongunitname")
    fun `invalid unit codes are rejected`(raw: String) {
        assertNull(UnitCode.parse(raw))
    }
}
